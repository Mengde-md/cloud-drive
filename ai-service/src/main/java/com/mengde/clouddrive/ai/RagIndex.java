package com.mengde.clouddrive.ai;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class RagIndex {
    public static final double MIN_SCORE = 0.78;
    public static final int TOP_K = 3;
    public static final int MAX_OWNERS = 32;
    public static final int MAX_DOCUMENTS = 8;
    public static final int MAX_CHUNKS = 128;
    private final boolean enabled;
    private final EmbeddingModel model;
    private final RagDocumentParser parser = new RagDocumentParser();
    private final Semaphore inference = new Semaphore(1);
    private final Map<Long, Corpus> corpora = new HashMap<>();
    private long revision;

    public RagIndex(boolean enabled, EmbeddingModel model) { this.enabled = enabled; this.model = model; }

    private void check(long owner) {
        if (!enabled) throw new AiFailure(503, "RAG_DISABLED");
        if (owner <= 0) throw new AiFailure(400, "RAG_OWNER_INVALID");
    }

    public DocumentInfo ingest(long owner, String name, String base64) {
        check(owner);
        RagDocumentParser.Parsed parsed = parser.parse(name, base64);
        if (!inference.tryAcquire()) throw new AiFailure(429, "RAG_BUSY");
        try {
            synchronized (this) { capacity(owner, parsed.chunks().size()); }
            String id = UUID.randomUUID().toString();
            List<TextSegment> chunks = new ArrayList<>();
            for (int i = 0; i < parsed.chunks().size(); i++) {
                chunks.add(TextSegment.from(parsed.chunks().get(i).text(), Metadata.from(Map.of(
                        "id", id + ":" + i, "documentId", id, "name", name, "ordinal", i))));
            }
            List<Embedding> vectors = model.embedAll(chunks).content();
            validateVectors(vectors, chunks.size());
            Indexed document = new Indexed(new DocumentInfo(id, name, parsed.sourceSha256(), chunks.size()),
                    List.copyOf(chunks), List.copyOf(vectors));
            synchronized (this) {
                capacity(owner, chunks.size());
                List<Indexed> documents = new ArrayList<>(corpora.getOrDefault(owner, Corpus.empty()).documents);
                documents.add(document);
                corpora.put(owner, rebuild(documents));
            }
            return document.info;
        } catch (AiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new AiFailure(503, "RAG_EMBEDDING_FAILED"); }
        finally { inference.release(); }
    }

    private void capacity(long owner, int added) {
        Corpus corpus = corpora.get(owner);
        if ((corpus == null && corpora.size() >= MAX_OWNERS)
                || (corpus != null && (corpus.documents.size() >= MAX_DOCUMENTS
                || corpus.documents.stream().mapToInt(d -> d.chunks.size()).sum() + added > MAX_CHUNKS))) {
            throw new AiFailure(409, "RAG_CAPACITY_EXCEEDED");
        }
    }

    private void validateVectors(List<Embedding> vectors, int count) {
        if (vectors == null || vectors.size() != count) throw new AiFailure(503, "RAG_EMBEDDING_FAILED");
        for (Embedding vector : vectors) {
            if (vector == null || vector.dimension() != 384) throw new AiFailure(503, "RAG_EMBEDDING_FAILED");
            double norm = 0;
            for (float value : vector.vector()) {
                if (!Float.isFinite(value)) throw new AiFailure(503, "RAG_EMBEDDING_FAILED");
                norm += value * (double) value;
            }
            if (norm == 0) throw new AiFailure(503, "RAG_EMBEDDING_FAILED");
        }
    }

    private Corpus rebuild(List<Indexed> documents) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        for (Indexed document : documents) store.addAll(document.vectors, document.chunks);
        return new Corpus(++revision, List.copyOf(documents), store);
    }

    public synchronized List<DocumentInfo> list(long owner) {
        check(owner);
        return corpora.getOrDefault(owner, Corpus.empty()).documents.stream().map(Indexed::info).toList();
    }

    public synchronized void delete(long owner, String id) {
        check(owner);
        Corpus corpus = corpora.get(owner);
        if (corpus == null || corpus.documents.stream().noneMatch(d -> d.info.documentId.equals(id))) {
            throw new AiFailure(404, "RAG_DOCUMENT_NOT_FOUND");
        }
        List<Indexed> remaining = corpus.documents.stream().filter(d -> !d.info.documentId.equals(id)).toList();
        if (remaining.isEmpty()) { corpora.remove(owner); revision++; }
        else corpora.put(owner, rebuild(remaining));
    }

    public Retrieval search(long owner, String question) {
        check(owner);
        if (question == null || question.isBlank() || question.length() > 512
                || question.chars().anyMatch(Character::isISOControl)) throw new AiFailure(400, "RAG_QUERY_INVALID");
        synchronized (this) { if (!corpora.containsKey(owner)) return new Retrieval(0, List.of()); }
        if (!inference.tryAcquire()) throw new AiFailure(429, "RAG_BUSY");
        try {
            Embedding query = model.embed(question.strip()).content();
            validateVectors(List.of(query), 1);
            synchronized (this) {
                Corpus corpus = corpora.get(owner);
                if (corpus == null) return new Retrieval(0, List.of());
                var matches = corpus.store.search(EmbeddingSearchRequest.builder().queryEmbedding(query)
                        .maxResults(TOP_K).minScore(MIN_SCORE).build()).matches();
                List<Hit> hits = matches.stream().map(m -> {
                    Metadata metadata = m.embedded().metadata();
                    return new Hit(metadata.getString("id"), metadata.getString("documentId"),
                            metadata.getString("name"), metadata.getInteger("ordinal"), m.score(), m.embedded().text());
                }).toList();
                return new Retrieval(corpus.revision, hits);
            }
        } catch (AiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new AiFailure(503, "RAG_EMBEDDING_FAILED"); }
        finally { inference.release(); }
    }

    public synchronized void requireCurrent(long owner, long expectedRevision) {
        Corpus corpus = corpora.get(owner);
        if (corpus == null || corpus.revision != expectedRevision) throw new AiFailure(409, "RAG_CORPUS_CHANGED");
    }

    public record DocumentInfo(String documentId, String name, String sourceSha256, int chunks) { }
    public record Hit(String id, String documentId, String name, int ordinal, double score, String text) { }
    public record Retrieval(long revision, List<Hit> hits) { }
    private record Indexed(DocumentInfo info, List<TextSegment> chunks, List<Embedding> vectors) { }
    private record Corpus(long revision, List<Indexed> documents, InMemoryEmbeddingStore<TextSegment> store) {
        static Corpus empty() { return new Corpus(0, List.of(), new InMemoryEmbeddingStore<>()); }
    }
}
