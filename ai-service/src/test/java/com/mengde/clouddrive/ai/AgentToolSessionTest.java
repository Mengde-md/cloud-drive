package com.mengde.clouddrive.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolSessionTest {
    private ToolExecutionRequest call(String name, String args) {
        return ToolExecutionRequest.builder().id("call_1").name(name).arguments(args).build();
    }
    @Test void identityIsConstructorBoundAndNotModelSupplied() {
        RagIndex index = mock(RagIndex.class);
        when(index.list(7)).thenReturn(List.of());
        var session = new AgentToolSession(index, 7);
        assertEquals("[]", session.execute(call(AgentToolCatalog.LIST, "{}")));
        verify(index).list(7);
        assertThrows(AiFailure.class, () -> session.execute(call(AgentToolCatalog.LIST, "{\"ownerId\":8}")));
        verifyNoMoreInteractions(index);
    }
    @Test void searchKeepsOnlyItsOwnLatestSnapshot() {
        RagIndex index = mock(RagIndex.class);
        var hit = new RagIndex.Hit("doc:0", "doc", "doc.txt", 0, .9, "verified source quote");
        when(index.search(7, "first")).thenReturn(new RagIndex.Retrieval(12, List.of(hit)));
        when(index.search(7, "second")).thenReturn(new RagIndex.Retrieval(13, List.of()));
        var session = new AgentToolSession(index, 7);
        session.execute(call(AgentToolCatalog.SEARCH, "{\"query\":\"first\"}"));
        assertEquals(List.of(hit), session.retrieval().hits());
        session.execute(call(AgentToolCatalog.SEARCH, "{\"query\":\"second\"}"));
        assertTrue(session.retrieval().hits().isEmpty());
        session.requireCurrent(); verify(index).requireCurrent(7, 13);
    }
    @Test void forbiddenNameFailsBeforeAnyRepositoryAction() {
        RagIndex index = mock(RagIndex.class);
        assertThrows(AiFailure.class, () -> new AgentToolSession(index, 7).execute(call("delete_document", "{}")));
        verifyNoInteractions(index);
        assertThrows(AiFailure.class, () -> new AgentToolSession(index, 0));
    }
    @Test void toolOutputBoundIsNotAClaimAboutProviderInputTokens() {
        RagIndex index = mock(RagIndex.class);
        when(index.search(7, "query")).thenReturn(new RagIndex.Retrieval(1, List.of(
                new RagIndex.Hit("id", "doc", "name.txt", 0, .9, "x".repeat(12001)))));
        var failure = assertThrows(AiFailure.class, () -> new AgentToolSession(index, 7)
                .execute(call(AgentToolCatalog.SEARCH, "{\"query\":\"query\"}")));
        assertEquals("AGENT_TOOL_OUTPUT_INVALID", failure.getMessage());
    }
}
