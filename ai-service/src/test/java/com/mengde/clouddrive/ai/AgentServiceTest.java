package com.mengde.clouddrive.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    private SummaryService generation() {
        SummaryService generation = mock(SummaryService.class);
        when(generation.controlled(eq(7L), any())).thenAnswer(call -> ((Callable<?>) call.getArgument(1)).call());
        return generation;
    }
    private RagIndex index() {
        RagIndex index = mock(RagIndex.class);
        when(index.list(7)).thenReturn(List.of(new RagIndex.DocumentInfo("doc", "doc.txt", "hash", 1)));
        return index;
    }
    private SummaryService.Generated tool(String id, String text, FinishReason finish) {
        var request = ToolExecutionRequest.builder().id(id).name(AgentToolCatalog.LIST).arguments("{}").build();
        var message = AiMessage.builder().text(text).toolExecutionRequests(List.of(request)).build();
        return new SummaryService.Generated("step", "deepseek-flash", ChatResponse.builder()
                .aiMessage(message).finishReason(finish).build(), null);
    }
    @Test void disabledOrInvalidInputNeverEntersGeneration() {
        var generation = generation(); var index = index();
        assertThrows(AiFailure.class, () -> new AgentService(index, generation, false).catalog());
        var service = new AgentService(index, generation, true);
        assertThrows(AiFailure.class, () -> service.ask(7, "test", false));
        assertThrows(AiFailure.class, () -> service.ask(7, "x".repeat(513), true));
        assertThrows(AiFailure.class, () -> service.ask(7, "line\nnext", true));
        verifyNoInteractions(generation, index);
    }
    @Test void emptyTenantRefusesWithoutModelOrToolCalls() {
        var generation = generation(); var index = mock(RagIndex.class);
        when(index.list(7)).thenReturn(List.of());
        var result = new AgentService(index, generation, true).ask(7, "question", true);
        assertEquals("NO_EVIDENCE", result.reason()); assertEquals(0, result.modelCalls());
        assertEquals(0, result.toolCalls()); assertTrue(result.usage().isEmpty()); verifyNoInteractions(generation);
    }
    @Test void repeatedToolCallIdFailsInsteadOfRepeatingAProposal() {
        var generation = generation(); var index = index();
        when(generation.toolChat(eq(7L), anyList(), anyList())).thenReturn(tool("same_id", "", FinishReason.TOOL_EXECUTION));
        var failure = assertThrows(AiFailure.class, () -> new AgentService(index, generation, true).ask(7, "question", true));
        assertEquals("AGENT_OUTPUT_INVALID", failure.getMessage());
        verify(index, times(2)).list(7); verify(generation, times(2)).toolChat(eq(7L), anyList(), anyList());
    }
    @Test void badFinishAndOversizeToolMessageFailBeforeDispatch() {
        for (var step : List.of(tool("one", "", FinishReason.STOP), tool("one", "x".repeat(2049), FinishReason.TOOL_EXECUTION))) {
            var generation = generation(); var index = index();
            when(generation.toolChat(eq(7L), anyList(), anyList())).thenReturn(step);
            assertThrows(AiFailure.class, () -> new AgentService(index, generation, true).ask(7, "question", true));
            verify(index).list(7); verifyNoMoreInteractions(index);
        }
    }
}
