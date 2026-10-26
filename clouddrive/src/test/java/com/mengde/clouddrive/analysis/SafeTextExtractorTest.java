package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafeTextExtractorTest {
    private final ObjectStorage objects = mock(ObjectStorage.class);
    private final AnalysisProperties properties = new AnalysisProperties();
    private final SafeTextExtractor extractor = new SafeTextExtractor(objects, properties);

    @Test
    void binaryDocumentsAreClassifiedBySuffixWithoutOpeningThem() throws Exception {
        for (String name : new String[]{"report.pdf", "report.docx", "photo.jpg", "bundle.zip", "payload.exe"}) {
            assertTrue(extractor.extract(job(name, 500)).isEmpty());
        }
        verifyNoInteractions(objects);
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromName("REPORT.PDF"));
        assertEquals(FileCategory.SPREADSHEET, FileCategory.fromName("财务.CSV"));
        assertEquals(FileCategory.CODE, FileCategory.fromName("hello.java"));
    }

    @Test
    void readsOnlyConfiguredPrefixAndHandlesPartialUtf8Character() throws Exception {
        properties.setMaxTextBytes(1024);
        byte[] bytes = ("a".repeat(1023) + "中文").getBytes(StandardCharsets.UTF_8);
        when(objects.open("key", 0, 1024)).thenReturn(new ByteArrayInputStream(bytes));
        var value = extractor.extract(job("notes.md", bytes.length)).orElseThrow();
        assertEquals("a".repeat(1023), value.text());
        assertTrue(value.truncated());
        verify(objects).open("key", 0, 1024);
    }

    @Test
    void rejectsBinaryPayloadPretendingToBeTextAndMalformedUtf8() throws Exception {
        when(objects.open(eq("key"), eq(0L), anyLong())).thenAnswer(invocation ->
                new ByteArrayInputStream(new byte[]{'a', 0, 'b'}));
        assertTrue(extractor.extract(job("payload.txt", 3)).isEmpty());
        when(objects.open(eq("key"), eq(0L), anyLong())).thenAnswer(invocation ->
                new ByteArrayInputStream(new byte[]{(byte) 0xff, (byte) 0xfe, 'x'}));
        assertTrue(extractor.extract(job("payload.txt", 3)).isEmpty());
    }

    @Test
    void validUtf8TextIsReturnedWithoutExecutingOrFollowingAnything() throws Exception {
        byte[] content = "中文笔记\nhttps://example.invalid/test\t脚本是数据".getBytes(StandardCharsets.UTF_8);
        when(objects.open("key", 0, content.length)).thenReturn(new ByteArrayInputStream(content));
        var value = extractor.extract(job("notes.txt", content.length)).orElseThrow();
        assertEquals(new String(content, StandardCharsets.UTF_8), value.text());
        assertFalse(value.truncated());
    }

    private AnalysisJob job(String name, long size) { return new AnalysisJob(1, 7, name, "key", size, "g", true); }
}
