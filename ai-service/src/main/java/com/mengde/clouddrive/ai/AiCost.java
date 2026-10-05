package com.mengde.clouddrive.ai;

import dev.langchain4j.model.output.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record AiCost(int inputTokens, int outputTokens, int totalTokens,
                     BigDecimal estimatedUsd, String rateSnapshot, boolean protocolFixture) {
    public static AiCost from(TokenUsage usage, boolean fixture) {
        if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null
                || usage.totalTokenCount() == null || usage.inputTokenCount() < 0
                || usage.outputTokenCount() < 0 || usage.totalTokenCount() < 0
                || (long) usage.inputTokenCount() + usage.outputTokenCount() != usage.totalTokenCount()) {
            throw new AiFailure(502, "AI_USAGE_INVALID");
        }
        // Peak, cache-miss rates deliberately overestimate discounted/cache-hit requests.
        BigDecimal estimate = BigDecimal.valueOf(usage.inputTokenCount()).multiply(new BigDecimal("0.3"))
                .add(BigDecimal.valueOf(usage.outputTokenCount()).multiply(new BigDecimal("1.2")))
                .divide(new BigDecimal("1000000"), 10, RoundingMode.HALF_UP);
        return new AiCost(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(),
                estimate, "deepseek-flash-2026-10-05-peak-cache-miss", fixture);
    }
}
