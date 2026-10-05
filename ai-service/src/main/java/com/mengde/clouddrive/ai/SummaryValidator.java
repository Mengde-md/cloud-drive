package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SummaryValidator {
    public static final int MAX_RESPONSE_CHARS = 12000;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                    .maxStringLength(MAX_RESPONSE_CHARS).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Summary validate(ChatResponse response) {
        if (response == null || response.finishReason() != FinishReason.STOP
                || response.aiMessage() == null || response.aiMessage().hasToolExecutionRequests()) {
            throw invalid();
        }
        return parse(response.aiMessage().text());
    }

    public Summary parse(String raw) {
        if (raw == null || raw.length() > MAX_RESPONSE_CHARS) throw invalid();
        try {
            JsonNode root = json.readTree(raw);
            Set<String> fields = new HashSet<>();
            if (root == null || !root.isObject()) throw invalid();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("summary", "keywords"))) throw invalid();
            String summary = text(root.get("summary"), 1000);
            JsonNode items = root.get("keywords");
            if (!items.isArray() || items.isEmpty() || items.size() > 5) throw invalid();
            List<String> keywords = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode item : items) {
                String keyword = text(item, 40);
                if (!unique.add(keyword.toLowerCase(Locale.ROOT))) throw invalid();
                keywords.add(keyword);
            }
            return new Summary(summary, List.copyOf(keywords));
        } catch (AiFailure failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    private String text(JsonNode value, int max) {
        if (value == null || !value.isTextual()) throw invalid();
        String text = value.textValue().strip();
        if (text.isEmpty() || text.length() > max || text.chars().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private AiFailure invalid() { return new AiFailure(502, "AI_OUTPUT_INVALID"); }
    public record Summary(String summary, List<String> keywords) { }
}
