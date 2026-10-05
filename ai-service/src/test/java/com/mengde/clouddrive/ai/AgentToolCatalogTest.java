package com.mengde.clouddrive.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolCatalogTest {
    @Test void specificationsAreExactlyTheTwoReadonlySchemas() {
        var tools = AgentToolCatalog.specifications();
        assertEquals(2, tools.size());
        assertEquals(AgentToolCatalog.LIST, tools.get(0).name());
        assertEquals(AgentToolCatalog.SEARCH, tools.get(1).name());
        assertFalse(tools.get(1).parameters().additionalProperties());
        assertEquals(java.util.List.of("query"), tools.get(1).parameters().required());
        assertTrue(AgentToolCatalog.definitions().stream().allMatch(AgentToolCatalog.Definition::readOnly));
        assertEquals("valid query", AgentToolCatalog.validate(AgentToolCatalog.SEARCH, "{\"query\":\"valid query\"}").query());
        assertNull(AgentToolCatalog.validate(AgentToolCatalog.LIST, "{}").query());
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{\"query\":12}", "{\"query\":null}",
            "{\"query\":\" \"}", "{\"query\":\"x\",\"ownerId\":8}", "{\"query\":\"x\",\"path\":\"a\"}",
            "{\"query\":\"first\",\"query\":\"second\"}", "{\"query\":\"x\"} {}", "{\"query\":\"line\\nnext\"}"})
    void badSchemaOrDuplicateArgumentsAreRejected(String raw) {
        assertEquals("AGENT_TOOL_INVALID", assertThrows(AiFailure.class,
                () -> AgentToolCatalog.validate(AgentToolCatalog.SEARCH, raw)).getMessage());
    }
    @Test void noToolCanAddAnOwnerOrFilePath() {
        assertThrows(AiFailure.class, () -> AgentToolCatalog.validate("delete_document", "{}"));
        assertThrows(AiFailure.class, () -> AgentToolCatalog.validate(AgentToolCatalog.LIST, "{\"ownerId\":8}"));
        assertThrows(AiFailure.class, () -> AgentToolCatalog.validate(AgentToolCatalog.SEARCH,
                AgentToolCatalog.write(java.util.Map.of("query", "x".repeat(513)))));
        assertThrows(AiFailure.class, () -> AgentToolCatalog.validate(AgentToolCatalog.LIST, " ".repeat(2049)));
    }
}
