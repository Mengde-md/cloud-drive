package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Day34FullChainProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void hashUsesRawBytesNotDecodedReplacementCharacters() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Day34FullChainProbe.sha256(new byte[0]));
        assertNotEquals(Day34FullChainProbe.sha256(new byte[]{(byte) 0xff}), Day34FullChainProbe.sha256(
                new String(new byte[]{(byte) 0xff}, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void validCitationMustBindDocumentAndExactSubstring() throws Exception {
        assertTrue(Day34FullChainProbe.citationMatches(json.readTree(
                "{\"documentId\":\"doc-a\",\"id\":\"doc-a:0\",\"quote\":\"thirty days\"}"), "doc-a", "Retention is thirty days."));
    }
    @Test void differentDocumentIsNotEvidenceEvenWithSameQuote() throws Exception {
        assertFalse(Day34FullChainProbe.citationMatches(json.readTree(
                "{\"documentId\":\"doc-b\",\"id\":\"doc-b:0\",\"quote\":\"thirty days\"}"), "doc-a", "Retention is thirty days."));
    }
    @Test void chunkIdentityAndQuoteAreIndependentlyChecked() throws Exception {
        assertFalse(Day34FullChainProbe.citationMatches(json.readTree(
                "{\"documentId\":\"doc-a\",\"id\":\"other:0\",\"quote\":\"thirty days\"}"), "doc-a", "Retention is thirty days."));
        assertFalse(Day34FullChainProbe.citationMatches(json.readTree(
                "{\"documentId\":\"doc-a\",\"id\":\"doc-a:0\",\"quote\":\"ninety days\"}"), "doc-a", "Retention is thirty days."));
    }
    @Test void shortOrMissingCitationCannotPass() throws Exception {
        assertFalse(Day34FullChainProbe.citationMatches(json.readTree("{}"), "", "anything"));
        assertFalse(Day34FullChainProbe.citationMatches(json.readTree(
                "{\"documentId\":\"doc-a\",\"id\":\"doc-a:0\",\"quote\":\"days\"}"), "doc-a", "days"));
    }
    @Test void deploymentMarkerCannotBeMissingOrAmbiguous() {
        assertThrows(IllegalArgumentException.class, () -> Day34FullChainProbe.deploymentPath("READY: gateway"));
        assertThrows(IllegalArgumentException.class, () -> Day34FullChainProbe.deploymentPath(
                "DEPLOYMENT READY: first\nDEPLOYMENT READY: second"));
        assertEquals(Path.of("owned run"), Day34FullChainProbe.deploymentPath("READY: auth\nDEPLOYMENT READY: owned run\n"));
    }
    @Test void multipartPreservesBinaryPayloadAndFinalDelimiter() {
        byte[] payload = new byte[]{0, (byte) 0xff, 13, 10, 42};
        byte[] encoded = Day34FullChainProbe.multipart("boundary", payload);
        byte[] suffix = "\r\n--boundary--\r\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(encoded, encoded.length - suffix.length - payload.length,
                encoded.length - suffix.length));
        assertTrue(new String(encoded, StandardCharsets.ISO_8859_1).contains("name=\"displayName\""));
    }
    @Test void multipartRejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class, () -> Day34FullChainProbe.multipart("evil\r\nheader", new byte[0]));
    }
}
