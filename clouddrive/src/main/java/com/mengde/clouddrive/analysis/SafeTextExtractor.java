package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.storage.ObjectStorage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class SafeTextExtractor {
    private final ObjectStorage objects;
    private final AnalysisProperties properties;

    public SafeTextExtractor(ObjectStorage objects, AnalysisProperties properties) {
        this.objects = objects;
        this.properties = properties;
    }

    /** No archive expansion, scripts, document macros or external links are executed. */
    Optional<ExtractedText> extract(AnalysisJob job) throws IOException {
        if (!FileCategory.supportsText(job.fileName()) || job.byteSize() <= 0) { return Optional.empty(); }
        int limit = (int) Math.min(job.byteSize(), properties.getMaxTextBytes());
        byte[] bytes;
        try (var input = objects.open(job.storageKey(), 0, limit)) {
            bytes = input.readNBytes(limit);
        }
        if (bytes.length == 0) { return Optional.empty(); }
        boolean truncated = job.byteSize() > bytes.length;
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = CharBuffer.allocate(bytes.length);
        var decoded = decoder.decode(ByteBuffer.wrap(bytes), chars, !truncated);
        if (decoded.isError()) { return Optional.empty(); }
        chars.flip();
        String text = chars.toString();
        long badControls = text.chars().filter(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t').count();
        if (text.indexOf('\0') >= 0 || badControls > 0 || text.isBlank()) { return Optional.empty(); }
        return Optional.of(new ExtractedText(text, truncated));
    }

    record ExtractedText(String text, boolean truncated) { }
}
