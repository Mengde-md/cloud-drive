package com.mengde.clouddrive.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.math.BigDecimal;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SummaryValidatorTest {
    private final SummaryValidator validator = new SummaryValidator();
    private static final String VALID = "{\"summary\":\"A summary\",\"keywords\":[\"Java\"]}";

    @Test void validTypedOutputIsImmutable() {
        var summary = validator.parse(VALID);
        assertEquals("A summary", summary.summary());
        assertThrows(UnsupportedOperationException.class, () -> summary.keywords().add("other"));
    }

    static Stream<String> malformed() {
        return Stream.of("", "[]", "null", "```json\n" + VALID + "\n```", VALID + " {}",
                "{\"summary\":\"one\",\"summary\":\"two\",\"keywords\":[\"a\"]}",
                "{\"summary\":1,\"keywords\":[\"a\"]}",
                "{\"summary\":\" \" ,\"keywords\":[\"a\"]}",
                "{\"summary\":\"ok\",\"keywords\":[]}",
                "{\"summary\":\"ok\",\"keywords\":[1]}",
                "{\"summary\":\"ok\",\"keywords\":[\"a\",\"A\"]}",
                "{\"summary\":\"ok\",\"keywords\":[\"a\"],\"action\":\"delete\"}",
                "{\"summary\":\"ok\"}",
                "{\"summary\":\"" + "s".repeat(1001) + "\",\"keywords\":[\"a\"]}",
                "{\"summary\":\"ok\",\"keywords\":[\"" + "k".repeat(41) + "\"]}",
                "{\"summary\":\"ok\\u0000\",\"keywords\":[\"a\"]}",
                "{\"summary\":\"ok\",\"keywords\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}",
                " ".repeat(12001));
    }
    @ParameterizedTest @MethodSource("malformed")
    void invalidJsonNeverBecomesBusinessOutput(String raw) {
        assertEquals("AI_OUTPUT_INVALID", assertThrows(AiFailure.class, () -> validator.parse(raw)).getMessage());
    }

    @Test void truncatedGenerationIsRejectedEvenWhenItsJsonLooksComplete() {
        var response = ChatResponse.builder().aiMessage(AiMessage.from(VALID)).finishReason(FinishReason.LENGTH).build();
        assertThrows(AiFailure.class, () -> validator.validate(response));
    }

    @Test void costUsesUsageAndDecimalArithmeticNotCharacterCount() {
        var cost = AiCost.from(new TokenUsage(100, 50, 150), true);
        assertEquals(new BigDecimal("0.0000900000"), cost.estimatedUsd());
        assertTrue(cost.protocolFixture());
    }

    @Test void missingNegativeOrInconsistentUsageCannotBeInvented() {
        assertAll(() -> assertThrows(AiFailure.class, () -> AiCost.from(null, false)),
                () -> assertThrows(AiFailure.class, () -> AiCost.from(new TokenUsage(-1, 1, 0), false)),
                () -> assertThrows(AiFailure.class, () -> AiCost.from(new TokenUsage(1, 2, 4), false)),
                () -> assertThrows(AiFailure.class, () -> AiCost.from(new TokenUsage(Integer.MAX_VALUE, 1, Integer.MIN_VALUE), false)));
    }
}
