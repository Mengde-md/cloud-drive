package com.mengde.clouddrive.ai;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

public final class RagDocumentParser {
    public static final int MAX_BYTES = 32768;
    public static final int MAX_CHARS = 12000;
    public static final int CHUNK_CHARS = 240;
    public static final int OVERLAP_CHARS = 32;

    public Parsed parse(String name, String base64) {
        if (name == null || !name.matches("[\\p{L}\\p{N} _.-]{1,96}\\.(?i:txt|md)")
                || base64 == null || base64.length() > 44000) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (text.isEmpty() || text.length() > MAX_CHARS || text.codePoints().anyMatch(c ->
                    Character.isISOControl(c) && c != '\n' && c != '\t')) throw invalid();
            List<TextSegment> chunks = DocumentSplitters.recursive(CHUNK_CHARS, OVERLAP_CHARS)
                    .split(Document.from(text));
            if (chunks.isEmpty() || chunks.size() > 96) throw invalid();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Parsed(name, hash, List.copyOf(chunks));
        } catch (AiFailure failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    private AiFailure invalid() { return new AiFailure(400, "RAG_DOCUMENT_INVALID"); }
    public record Parsed(String name, String sourceSha256, List<TextSegment> chunks) { }
}
