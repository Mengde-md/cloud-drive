package com.mengde.clouddrive.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class AgentTelemetry {
    private static final Logger log = LoggerFactory.getLogger(AgentTelemetry.class);
    private static final Set<String> CODES = Set.of("NONE", "NO_EVIDENCE", "MODEL_REFUSED", "AGENT_DISABLED",
            "AGENT_INPUT_INVALID", "AGENT_OUTPUT_INVALID", "AGENT_TOOL_INVALID", "AGENT_TOOL_OUTPUT_INVALID",
            "AGENT_LIMIT_EXCEEDED", "AGENT_INTERRUPTED", "RAG_DISABLED", "RAG_OWNER_INVALID", "RAG_OUTPUT_INVALID",
            "RAG_CORPUS_CHANGED", "RAG_EMBEDDING_FAILED", "AI_DISABLED", "AI_BUSY", "AI_TIMEOUT",
            "AI_INTERRUPTED", "AI_UPSTREAM_FAILED", "AI_USAGE_INVALID");
    private final MeterRegistry meters;
    public AgentTelemetry(MeterRegistry meters) { this.meters = java.util.Objects.requireNonNull(meters); }
    public Span begin() { return new Span(); }
    private static String code(String value) { return value == null ? "NONE" : CODES.contains(value) ? value : "UNKNOWN_FAILURE"; }
    private static String id(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "none"; }
    public final class Span {
        private final String runId = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private int modelCalls, completedSteps, toolCalls;
        private BigDecimal estimate = BigDecimal.ZERO;
        private boolean closed;
        private Snapshot snapshot;
        private Span() { log.info("event=agent_start runId={}", runId); }
        public synchronized void modelStarted() {
            if (closed) return;
            modelCalls++;
            meters.counter("clouddrive.agent.model.calls").increment();
        }
        public synchronized void modelFinished(SummaryService.Generated step) {
            if (closed || step == null || step.usage() == null) return;
            AiCost cost = step.usage(); completedSteps++; estimate = estimate.add(cost.estimatedUsd());
            String mode = cost.protocolFixture() ? "fixture" : "provider";
            meters.counter("clouddrive.agent.tokens", "kind", "input", "mode", mode).increment(cost.inputTokens());
            meters.counter("clouddrive.agent.tokens", "kind", "output", "mode", mode).increment(cost.outputTokens());
            meters.counter("clouddrive.agent.estimated.usd", "mode", mode).increment(cost.estimatedUsd().doubleValue());
            log.info("event=agent_model runId={} requestId={} step={} mode={} inputTokens={} outputTokens={} estimatedUsd={}",
                    runId, id(step.requestId()), modelCalls, mode, cost.inputTokens(), cost.outputTokens(), cost.estimatedUsd());
        }
        public synchronized void toolFinished(String name) {
            if (closed) return;
            toolCalls++;
            String tool = AgentToolCatalog.LIST.equals(name) || AgentToolCatalog.SEARCH.equals(name) ? name : "unknown";
            meters.counter("clouddrive.agent.tool.calls", "tool", tool).increment();
            log.info("event=agent_tool runId={} tool={} completed={}", runId, tool, toolCalls);
        }
        public synchronized Snapshot finish(String outcome, String reason, String requestId) {
            if (closed) return snapshot;
            closed = true;
            String status = "ANSWERED".equals(outcome) || "REFUSED".equals(outcome) ? outcome : "FAILED";
            String safeCode = code(reason);
            String costStatus = modelCalls == 0 ? "not_called" : completedSteps == modelCalls ? "estimated" : "incomplete";
            long nanos = Math.max(0, System.nanoTime() - started);
            meters.counter("clouddrive.agent.requests", "outcome", status, "code", safeCode).increment();
            meters.timer("clouddrive.agent.duration", "outcome", status, "code", safeCode).record(nanos, TimeUnit.NANOSECONDS);
            snapshot = new Snapshot(runId, status, safeCode, modelCalls, completedSteps, toolCalls, estimate, costStatus);
            log.info("event=agent_complete runId={} requestId={} outcome={} code={} modelCalls={} completedSteps={} toolCalls={} elapsedMillis={} knownEstimatedUsd={} costStatus={}",
                    runId, id(requestId), status, safeCode, modelCalls, completedSteps, toolCalls,
                    TimeUnit.NANOSECONDS.toMillis(nanos), estimate, costStatus);
            return snapshot;
        }
    }
    public record Snapshot(String runId, String outcome, String code, int modelCalls, int completedSteps,
                           int toolCalls, BigDecimal knownEstimatedUsd, String costStatus) { }
}
