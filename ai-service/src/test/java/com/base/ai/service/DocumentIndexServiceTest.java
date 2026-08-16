package com.base.ai.service;

import com.base.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIndexServiceTest {

    @Test
    void sameFileIdMustBeIsolatedBetweenUsers() {
        AiProperties properties = new AiProperties();
        properties.getIndex().setChunkSize(100);
        properties.getIndex().setChunkOverlap(0);
        DocumentIndexService service = new DocumentIndexService(properties, new TextChunkingService());

        service.indexDocument(1L, 100L, "alice.txt", "Alice 的合同包含保密条款。 ");
        service.indexDocument(2L, 100L, "bob.txt", "Bob 的文档仅包含预算信息。 ");

        List<String> aliceChunks = service.searchRelevantChunks(1L, 100L, "保密条款", 3);
        List<String> bobChunks = service.searchRelevantChunks(2L, 100L, "保密条款", 3);

        assertEquals(1, aliceChunks.size());
        assertTrue(aliceChunks.get(0).contains("Alice"));
        assertTrue(bobChunks.isEmpty());
    }
}
