package com.mengde.clouddrive.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static com.mengde.clouddrive.ai.RagDocumentParserTest.encoded;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RagIndexTest {
    static final EmbeddingModel REAL_MODEL = new AllMiniLmL6V2QuantizedEmbeddingModel(Runnable::run);
    static final String TRASH = "Deleted files are moved into the recycle bin for thirty days. "
            + "Users can restore deleted files during this retention period.";

    @Test void realOnnxRetrievalIsScopedByOwnerAndDeletion() {
        RagIndex index = new RagIndex(true, REAL_MODEL);
        var first = index.ingest(7, "trash.txt", encoded(TRASH));
        index.ingest(8, "private.txt", encoded(TRASH + " Tenant eight private marker."));
        var retrieval = index.search(7, "How long are deleted files kept in the recycle bin?");
        assertFalse(retrieval.hits().isEmpty());
        assertTrue(retrieval.hits().stream().allMatch(h -> h.documentId().equals(first.documentId())));
        assertThrows(AiFailure.class, () -> index.delete(8, first.documentId()));
        index.delete(7, first.documentId());
        assertTrue(index.search(7, "How long are deleted files kept in the recycle bin?").hits().isEmpty());
        assertEquals(1, index.list(8).size());
    }
    @Test void newProcessEquivalentStartsEmptyAndOldRevisionFailsEvenAfterReimport() {
        RagIndex index = new RagIndex(true, REAL_MODEL);
        var document = index.ingest(7, "trash.txt", encoded(TRASH));
        long revision = index.search(7, TRASH).revision();
        index.delete(7, document.documentId());
        index.ingest(7, "trash.txt", encoded(TRASH));
        assertEquals("RAG_CORPUS_CHANGED", assertThrows(AiFailure.class, () -> index.requireCurrent(7, revision)).getMessage());
        assertTrue(new RagIndex(true, REAL_MODEL).list(7).isEmpty());
    }
    @Test void emptyCorpusDoesNotInvokeEmbeddingAndDisabledStateIsExplicit() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        RagIndex index = new RagIndex(true, model);
        assertTrue(index.search(7, "question").hits().isEmpty());
        verifyNoInteractions(model);
        assertEquals("RAG_DISABLED", assertThrows(AiFailure.class, () -> new RagIndex(false, null).list(7)).getMessage());
        assertThrows(AiFailure.class, () -> index.list(0));
        assertThrows(AiFailure.class, () -> index.search(7, "bad\nquery"));
        assertThrows(AiFailure.class, () -> index.search(7, "x".repeat(513)));
    }
    static EmbeddingModel constantModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> {
                float[] vector = new float[384]; vector[0] = 1; return Embedding.from(vector);
            }).toList());
        });
        return model;
    }
    @Test void documentAndOwnerQuotasAreBoundedAndReleasedByDelete() {
        RagIndex index = new RagIndex(true, constantModel());
        var first = index.ingest(7, "doc.txt", encoded("sample"));
        for (int i = 1; i < RagIndex.MAX_DOCUMENTS; i++) index.ingest(7, "doc.txt", encoded("sample"));
        assertEquals("RAG_CAPACITY_EXCEEDED", assertThrows(AiFailure.class,
                () -> index.ingest(7, "doc.txt", encoded("sample"))).getMessage());
        index.delete(7, first.documentId());
        index.ingest(7, "doc.txt", encoded("sample"));
        RagIndex owners = new RagIndex(true, constantModel());
        for (int i = 1; i <= RagIndex.MAX_OWNERS; i++) owners.ingest(i, "doc.txt", encoded("sample"));
        assertThrows(AiFailure.class, () -> owners.ingest(100, "doc.txt", encoded("sample")));
        owners.delete(1, owners.list(1).get(0).documentId());
        assertNotNull(owners.ingest(100, "doc.txt", encoded("sample")));
    }
    @Test void invalidVectorNeverPublishesPartialDocument() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[384]))));
        RagIndex index = new RagIndex(true, model);
        assertEquals("RAG_EMBEDDING_FAILED", assertThrows(AiFailure.class,
                () -> index.ingest(7, "doc.txt", encoded("sample"))).getMessage());
        assertTrue(index.list(7).isEmpty());
        assertThrows(AiFailure.class, () -> index.ingest(7, "long.txt", encoded("word ".repeat(500))));
        assertTrue(index.list(7).isEmpty());
    }
    @Test void chunkQuotaAppliesBeforeDocumentQuota() {
        RagIndex index = new RagIndex(true, constantModel());
        String content = encoded("word ".repeat(2200));
        int perDocument = new RagDocumentParser().parse("long.txt", content).chunks().size();
        int permitted = RagIndex.MAX_CHUNKS / perDocument;
        assertTrue(permitted < RagIndex.MAX_DOCUMENTS);
        for (int i = 0; i < permitted; i++) index.ingest(7, "long.txt", content);
        assertEquals("RAG_CAPACITY_EXCEEDED", assertThrows(AiFailure.class,
                () -> index.ingest(7, "long.txt", content)).getMessage());
        assertEquals(permitted, index.list(7).size());
    }
    @Test void wrongDimensionAndNonFiniteVectorFailWithoutMutation() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] notFinite = new float[384]; notFinite[0] = Float.NaN;
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[10]))),
                Response.from(List.of(Embedding.from(notFinite))), Response.from(List.of()));
        RagIndex index = new RagIndex(true, model);
        for (int i = 0; i < 3; i++) assertEquals("RAG_EMBEDDING_FAILED", assertThrows(AiFailure.class,
                () -> index.ingest(7, "doc.txt", encoded("sample"))).getMessage());
        assertTrue(index.list(7).isEmpty());
    }
    @Test void concurrentInferenceFailsFastAndFailedWorkerReleasesItsSlot() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
            throw new IllegalStateException("private-native-failure");
        });
        RagIndex index = new RagIndex(true, model);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> assertThrows(AiFailure.class,
                    () -> index.ingest(7, "doc.txt", encoded("sample"))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("RAG_BUSY", assertThrows(AiFailure.class,
                    () -> index.ingest(8, "doc.txt", encoded("sample"))).getMessage());
            release.countDown(); pending.get(3, TimeUnit.SECONDS);
            assertEquals("RAG_EMBEDDING_FAILED", assertThrows(AiFailure.class,
                    () -> index.ingest(8, "doc.txt", encoded("sample"))).getMessage());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
