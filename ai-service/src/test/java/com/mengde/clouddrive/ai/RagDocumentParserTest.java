package com.mengde.clouddrive.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class RagDocumentParserTest {
    private final RagDocumentParser parser = new RagDocumentParser();
    static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }

    @Test void parsesBomUnicodeAndNewlinesWithoutRenderingMarkdown() {
        var parsed = parser.parse("notes.md", encoded("\uFEFF# Notes\r\n\u4e91\u76d8\ttext\rnext"));
        assertEquals("notes.md", parsed.name());
        assertTrue(parsed.chunks().get(0).text().contains("\u4e91\u76d8"));
        assertFalse(parsed.chunks().get(0).text().contains("\r"));
        assertTrue(parsed.chunks().get(0).text().contains("# Notes"));
        assertEquals(64, parsed.sourceSha256().length());
        assertEquals(parsed.sourceSha256(), parser.parse("other.txt", encoded("\uFEFF# Notes\r\n\u4e91\u76d8\ttext\rnext")).sourceSha256());
    }
    @Test void boundedSplitterRetainsTailAndOverlapsLongText() {
        var chunks = parser.parse("long.txt", encoded("abcd".repeat(400) + "TAIL_MARKER")).chunks();
        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= RagDocumentParser.CHUNK_CHARS));
        assertTrue(chunks.get(chunks.size() - 1).text().contains("TAIL_MARKER"));
        String first = chunks.get(0).text();
        assertTrue(chunks.get(1).text().startsWith(first.substring(first.length() - RagDocumentParser.OVERLAP_CHARS)));
        assertThrows(UnsupportedOperationException.class, () -> chunks.clear());
    }
    @ParameterizedTest @ValueSource(strings = {"secret.pdf", "../doc.txt", "C:\\doc.txt", "http://host/a.md", "bad\n.txt", "empty"})
    void rejectsUnsupportedOrPathLikeNames(String name) {
        assertEquals("RAG_DOCUMENT_INVALID", assertThrows(AiFailure.class,
                () -> parser.parse(name, encoded("sample"))).getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"", "%%%", "wyg=", "AA==", "IA=="})
    void rejectsEmptyMalformedUtf8ControlsAndBlank(String content) {
        assertThrows(AiFailure.class, () -> parser.parse("doc.txt", content));
    }
    @Test void limitsDecodedBytesCharactersAndEncodedEnvelope() {
        assertThrows(AiFailure.class, () -> parser.parse("doc.txt", encoded("x".repeat(12001))));
        assertThrows(AiFailure.class, () -> parser.parse("doc.txt", encoded("x".repeat(32769))));
        assertThrows(AiFailure.class, () -> parser.parse("doc.txt", "A".repeat(44001)));
        assertThrows(AiFailure.class, () -> parser.parse(null, encoded("sample")));
        assertThrows(AiFailure.class, () -> parser.parse("doc.txt", null));
    }
}
