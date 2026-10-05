package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.*;

public final class RagAnswerValidator {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(12000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Answer validate(ChatResponse response, List<RagIndex.Hit> evidence) {
        if (response == null || response.finishReason() != FinishReason.STOP || response.aiMessage() == null
                || response.aiMessage().hasToolExecutionRequests()) throw invalid();
        return parse(response.aiMessage().text(), evidence);
    }

    public Answer parse(String raw, List<RagIndex.Hit> evidence) {
        if (raw == null || raw.length() > 12000) throw invalid();
        try {
            JsonNode root = json.readTree(raw);
            fields(root, Set.of("status", "answer", "citations"));
            String status = text(root.get("status"), 7, 8);
            JsonNode citations = root.get("citations");
            if (!citations.isArray() || citations.size() > RagIndex.TOP_K) throw invalid();
            if (status.equals("REFUSED")) {
                if (!root.path("answer").isTextual() || !root.path("answer").textValue().isEmpty()
                        || !citations.isEmpty()) throw invalid();
                return new Answer("REFUSED", "", List.of());
            }
            if (!status.equals("ANSWERED") || citations.isEmpty()) throw invalid();
            String answer = text(root.get("answer"), 1, 1500);
            List<Citation> accepted = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode item : citations) {
                fields(item, Set.of("id", "quote"));
                String id = text(item.get("id"), 1, 64);
                String quote = text(item.get("quote"), 8, RagDocumentParser.CHUNK_CHARS);
                if (!unique.add(id)) throw invalid();
                RagIndex.Hit hit = evidence.stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow(this::invalid);
                if (!hit.text().contains(quote)) throw invalid();
                accepted.add(new Citation(id, hit.documentId(), hit.name(), hit.ordinal(), hit.score(), quote));
            }
            return new Answer(status, answer, List.copyOf(accepted));
        } catch (AiFailure failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    private void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid();
    }
    private String text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        if (value.isBlank() || value.length() < min || value.length() > max
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) throw invalid();
        return value;
    }
    private AiFailure invalid() { return new AiFailure(502, "RAG_OUTPUT_INVALID"); }
    public record Citation(String id, String documentId, String name, int ordinal, double score, String quote) { }
    public record Answer(String status, String answer, List<Citation> citations) { }
}
