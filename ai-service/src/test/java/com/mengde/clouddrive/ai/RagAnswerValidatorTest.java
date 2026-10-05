package com.mengde.clouddrive.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RagAnswerValidatorTest {
    static final String VALID = "{\"status\":\"ANSWERED\",\"answer\":\"Entries move into trash.\","
            + "\"citations\":[{\"id\":\"doc:0\",\"quote\":\"Deletion moves entries into the trash.\"}]}";
    private final RagAnswerValidator validator = new RagAnswerValidator();
    private final List<RagIndex.Hit> hits = List.of(new RagIndex.Hit("doc:0", "doc", "trash.txt", 0, .9,
            "Deletion moves entries into the trash. Restoration preserves the original identifier."));

    @Test void acceptsExactQuoteAndUsesServerOwnedSourceMetadata() {
        var answer = validator.parse(VALID, hits);
        assertEquals("trash.txt", answer.citations().get(0).name());
        assertEquals(.9, answer.citations().get(0).score());
        assertThrows(UnsupportedOperationException.class, () -> answer.citations().clear());
    }
    @Test void allowsExplicitRefusalButNotAnAnsweredEmptyList() {
        assertEquals("REFUSED", validator.parse("{\"status\":\"REFUSED\",\"answer\":\"\",\"citations\":[]}", hits).status());
        assertThrows(AiFailure.class, () -> validator.parse(VALID, List.of()));
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "[]", "null", "```json\n{}\n```", "{\"status\":\"ANSWERED\",\"answer\":\"ok\",\"citations\":[]}",
            "{\"status\":\"REFUSED\",\"answer\":\"invented\",\"citations\":[]}",
            "{\"status\":\"REFUSED\",\"answer\":0,\"citations\":[]}",
            "{\"status\":\"REFUSED\",\"answer\":\"\",\"citations\":{},\"action\":\"delete\"}",
            "{\"status\":\"REFUSED\",\"status\":\"REFUSED\",\"answer\":\"\",\"citations\":[]}",
            "{\"status\":\"REFUSED\",\"answer\":\"\",\"citations\":[]} {}",
            "{\"status\":\"UNKNOWN\",\"answer\":\"\",\"citations\":[]}",
            "{\"status\":\"ANSWERED\",\"answer\":0,\"citations\":[{\"id\":\"doc:0\",\"quote\":\"Deletion moves entries into the trash.\"}]}"
    }) void rejectsWrongSchemaAndDuplicateOrTrailingJson(String raw) {
        assertEquals("RAG_OUTPUT_INVALID", assertThrows(AiFailure.class, () -> validator.parse(raw, hits)).getMessage());
    }
    @Test void rejectsForgedIdQuoteExtraMetadataDuplicateCitationAndOversize() {
        assertThrows(AiFailure.class, () -> validator.parse(VALID.replace("doc:0", "other:0"), hits));
        assertThrows(AiFailure.class, () -> validator.parse(VALID.replace("Deletion moves", "Deletion destroys"), hits));
        assertThrows(AiFailure.class, () -> validator.parse(VALID.replace("\"id\":", "\"name\":\"fake.txt\",\"id\":"), hits));
        String item = "{\"id\":\"doc:0\",\"quote\":\"Deletion moves entries into the trash.\"}";
        assertThrows(AiFailure.class, () -> validator.parse(VALID.replace(item, item + "," + item), hits));
        assertThrows(AiFailure.class, () -> validator.parse(VALID.replace("Entries move into trash.", "a".repeat(1501)), hits));
        assertThrows(AiFailure.class, () -> validator.parse(" ".repeat(12001), hits));
        assertThrows(AiFailure.class, () -> validator.parse(null, hits));
    }
    @Test void rejectsMissingOrTruncatedModelResponse() {
        assertThrows(AiFailure.class, () -> validator.validate(null, hits));
        assertThrows(AiFailure.class, () -> validator.validate(ChatResponse.builder().aiMessage(AiMessage.from(VALID))
                .finishReason(FinishReason.LENGTH).build(), hits));
        assertEquals("ANSWERED", validator.validate(ChatResponse.builder().aiMessage(AiMessage.from(VALID))
                .finishReason(FinishReason.STOP).build(), hits).status());
    }
    @Test void toolRequestIsRejectedAndNeverExecuted() {
        var tool = ToolExecutionRequest.builder().id("test-tool").name("deleteFile").arguments("{}").build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from(List.of(tool))).finishReason(FinishReason.STOP).build();
        assertThrows(AiFailure.class, () -> validator.validate(response, hits));
    }
}
