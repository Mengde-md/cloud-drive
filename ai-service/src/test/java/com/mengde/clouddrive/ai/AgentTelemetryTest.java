package com.mengde.clouddrive.ai;

import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AgentTelemetryTest {
    private SummaryService.Generated step() {
        return new SummaryService.Generated("fixture-1", "deepseek-flash", null,
                AiCost.from(new TokenUsage(100, 50, 150), true));
    }
    @Test void completedStepsAndCostAreSeparateFromAttempts() {
        var meters = new SimpleMeterRegistry();
        try {
            var span = new AgentTelemetry(meters).begin();
            span.modelStarted(); span.modelFinished(step()); span.toolFinished(AgentToolCatalog.SEARCH);
            span.modelStarted(); var result = span.finish("FAILED", "AI_TIMEOUT", null);
            assertEquals(2, result.modelCalls()); assertEquals(1, result.completedSteps());
            assertEquals(1, result.toolCalls()); assertEquals("incomplete", result.costStatus());
            assertEquals(0, new BigDecimal("0.00009").compareTo(result.knownEstimatedUsd()));
            assertEquals(100, meters.get("clouddrive.agent.tokens").tags("kind", "input", "mode", "fixture").counter().count());
            assertEquals(1, meters.get("clouddrive.agent.duration").timer().count());
        } finally { meters.close(); }
    }
    @Test void zeroCallsAreNotZeroBillAndUnknownLabelsAreSanitized(CapturedOutput logs) {
        var meters = new SimpleMeterRegistry();
        try {
            var span = new AgentTelemetry(meters).begin();
            var result = span.finish(null, "secret-canary\nforged-event", "private-id-canary\nforged-event");
            assertEquals("FAILED", result.outcome()); assertEquals("UNKNOWN_FAILURE", result.code());
            assertEquals("not_called", result.costStatus());
            assertFalse(logs.getAll().contains("secret-canary")); assertFalse(logs.getAll().contains("private-id-canary"));
            meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                    assertTrue(Set.of("outcome", "code").contains(tag.getKey()))));
        } finally { meters.close(); }
    }
    @Test void finishIsIdempotentAndLateWorkerCallbacksCannotCountAgain() {
        var meters = new SimpleMeterRegistry();
        try {
            var span = new AgentTelemetry(meters).begin(); span.modelStarted();
            var first = span.finish("FAILED", "AI_TIMEOUT", null);
            span.modelStarted(); span.modelFinished(step()); span.toolFinished("secret-tool-canary");
            assertSame(first, span.finish("ANSWERED", null, "fixture-2"));
            assertEquals(1, meters.get("clouddrive.agent.model.calls").counter().count());
            assertEquals(1, meters.get("clouddrive.agent.requests").counter().count());
            assertNull(meters.find("clouddrive.agent.tokens").counter());
        } finally { meters.close(); }
    }
    @Test void concurrentCompletionRecordsOneRequest() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            var span = new AgentTelemetry(meters).begin(); var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> span.finish("REFUSED", "NO_EVIDENCE", null));
                var second = pool.submit(() -> span.finish("FAILED", "AI_TIMEOUT", null));
                assertSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
                assertEquals(1, meters.get("clouddrive.agent.requests").counter().count());
            } finally { pool.shutdownNow(); }
        } finally { meters.close(); }
    }
    @Test void successfulFixtureCostIsAnEstimateAndLabelsRemainFinite(CapturedOutput logs) {
        var meters = new SimpleMeterRegistry();
        try {
            var span = new AgentTelemetry(meters).begin(); span.modelStarted(); span.modelFinished(step());
            span.toolFinished(AgentToolCatalog.LIST); var result = span.finish("ANSWERED", null, "fixture-1");
            assertEquals("estimated", result.costStatus()); assertEquals("NONE", result.code());
            assertTrue(logs.getAll().contains("event=agent_complete")); assertTrue(logs.getAll().contains("mode=fixture"));
            meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
                assertTrue(Set.of("outcome", "code", "kind", "mode", "tool").contains(tag.getKey()));
                assertFalse(tag.getValue().contains(result.runId()));
                assertNotEquals("fixture-1", tag.getValue());
            }));
        } finally { meters.close(); }
    }
}
