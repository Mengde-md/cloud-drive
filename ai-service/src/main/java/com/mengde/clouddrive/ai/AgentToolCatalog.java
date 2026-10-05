package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.*;

public final class AgentToolCatalog {
    public static final String LIST = "rag_list_documents";
    public static final String SEARCH = "rag_search";
    private static final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(2048).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, Object> EMPTY = Map.of("type", "object", "properties", Map.of(),
            "required", List.of(), "additionalProperties", false);
    private static final Map<String, Object> QUERY = Map.of("type", "object", "properties", Map.of(
            "query", Map.of("type", "string", "description", "Nonblank search query, 1 to 512 characters, no control characters.")),
            "required", List.of("query"), "additionalProperties", false);
    private static final List<Definition> definitions = List.of(
            new Definition(LIST, "List only the authenticated user's imported RAG document metadata. Read-only.", EMPTY, true),
            new Definition(SEARCH, "Search only the authenticated user's imported RAG evidence. Read-only; no file access.", QUERY, true));

    private AgentToolCatalog() { }
    public static List<Definition> definitions() { return definitions; }
    public static List<ToolSpecification> specifications() {
        return definitions.stream().map(d -> ToolSpecification.fromJson(write(Map.of(
                "name", d.name(), "description", d.description(), "parameters", d.inputSchema())))).toList();
    }
    public static Arguments validate(String name, String raw) {
        if (!LIST.equals(name) && !SEARCH.equals(name)) throw invalid();
        if (raw == null || raw.length() > 2048) throw invalid();
        try {
            JsonNode input = json.readTree(raw);
            if (input == null || !input.isObject()) throw invalid();
            if (LIST.equals(name)) {
                if (!input.isEmpty()) throw invalid();
                return new Arguments(name, null);
            }
            if (input.size() != 1 || !input.has("query") || !input.get("query").isTextual()) throw invalid();
            String query = input.get("query").textValue();
            if (query.isBlank() || query.length() > 512 || query.chars().anyMatch(Character::isISOControl)) throw invalid();
            return new Arguments(name, query);
        } catch (AiFailure failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }
    public static String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new AiFailure(502, "AGENT_TOOL_INVALID"); }
    }
    private static AiFailure invalid() { return new AiFailure(502, "AGENT_TOOL_INVALID"); }
    public record Definition(String name, String description, Map<String, Object> inputSchema, boolean readOnly) { }
    public record Arguments(String name, String query) { }
}
