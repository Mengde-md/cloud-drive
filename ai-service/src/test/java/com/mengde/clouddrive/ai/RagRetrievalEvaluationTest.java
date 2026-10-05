package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.mengde.clouddrive.ai.RagDocumentParserTest.encoded;
import static org.junit.jupiter.api.Assertions.*;

class RagRetrievalEvaluationTest {
    @Test void evaluatesRealLocalEmbeddingRecallRefusalAndTenantLeakage() throws Exception {
        RagIndex index = new RagIndex(true, RagIndexTest.REAL_MODEL);
        for (var source : RagEvaluationData.SOURCES) index.ingest(7, source.name(), encoded(source.text()));
        index.ingest(8, "private.txt", encoded("The mass of the planet Jupiter is 1.898 times ten to the power of 27 kilograms."));
        int positives = 0, recalled = 0, negatives = 0, refused = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var test : RagEvaluationData.CASES) {
            var hits = index.search(7, test.question()).hits();
            assertTrue(hits.size() <= RagIndex.TOP_K);
            assertTrue(hits.stream().allMatch(h -> h.score() >= RagIndex.MIN_SCORE && h.score() <= 1));
            assertTrue(hits.stream().noneMatch(h -> h.name().equals("private.txt")));
            boolean passed;
            if (test.expectedSource() != null) {
                positives++;
                passed = hits.stream().anyMatch(h -> h.name().equals(test.expectedSource()));
                if (passed) recalled++;
            } else { negatives++; passed = hits.isEmpty(); if (passed) refused++; }
            rows.add(Map.of("id", test.id(), "question", test.question(), "passed", passed, "hits", hits));
        }
        Map<String, Object> report = Map.of("model", "all-MiniLM-L6-v2-q", "dimensions", 384,
                "minScore", RagIndex.MIN_SCORE, "topK", RagIndex.TOP_K, "positiveCases", positives,
                "recalled", recalled, "negativeCases", negatives, "refused", refused, "cases", rows);
        Files.createDirectories(Path.of("build-output"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("build-output/rag-evaluation.json").toFile(), report);
        System.out.println("RAG_EVALUATION recall@3=" + recalled + "/" + positives + " negative-refusal=" + refused + "/" + negatives);
        assertTrue(recalled / (double) positives >= .8, report.toString());
        assertEquals(negatives, refused, report.toString());
    }
}
