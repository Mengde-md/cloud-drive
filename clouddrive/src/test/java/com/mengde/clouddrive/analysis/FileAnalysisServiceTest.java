package com.mengde.clouddrive.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileAnalysisServiceTest {
    private final FileAnalysisRepository repository = mock(FileAnalysisRepository.class);
    private final AnalysisSettingsService settings = mock(AnalysisSettingsService.class);
    private final DeepSeekAnalysisClient deepseek = mock(DeepSeekAnalysisClient.class);
    private final SafeTextExtractor extractor = mock(SafeTextExtractor.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AnalysisJob job = DeepSeekAnalysisClientTest.job(true);
    private final FileAnalysisResponse pending = new FileAnalysisResponse(31, "notes.txt", "DOCUMENT", "EXTENSION",
            "PENDING", "分析中", "UNREVIEWED", "未人工审核", null, null, null, null);

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(settings.reviewEnabled()).thenReturn(true);
        when(repository.start(7, 31, true, false)).thenReturn(job);
        when(repository.find(7, 31, true)).thenReturn(pending);
        when(repository.isCurrentAndActive(job)).thenReturn(true);
    }

    @Test
    void missingKeyFallsBackWithoutOpeningContentOrClaimingApproval() {
        FileAnalysisService service = service(Runnable::run);
        service.schedule(7, 31);
        AnalysisResult result = saved();
        assertEquals("EXTENSION", result.source());
        assertEquals("FALLBACK", result.status());
        assertEquals("UNREVIEWED", result.reviewStatus());
        assertEquals(FileCategory.DOCUMENT, result.category());
        verifyNoInteractions(extractor);
    }

    @Test
    void unsupportedBinaryFallsBackWithoutCallingProvider() throws Exception {
        when(deepseek.configured()).thenReturn(true);
        when(extractor.extract(job)).thenReturn(Optional.empty());
        service(Runnable::run).schedule(7, 31);
        assertEquals("UNREVIEWED", saved().reviewStatus());
        verify(deepseek, never()).analyze(any(), any());
    }

    @Test
    void timeoutNeverTurnsIntoAnApprovedReview() throws Exception {
        when(deepseek.configured()).thenReturn(true);
        var text = new SafeTextExtractor.ExtractedText("note", false);
        when(extractor.extract(job)).thenReturn(Optional.of(text));
        when(deepseek.analyze(job, text)).thenThrow(new HttpTimeoutException("timeout"));
        service(Runnable::run).schedule(7, 31);
        AnalysisResult result = saved();
        assertEquals("FALLBACK", result.status());
        assertEquals("UNREVIEWED", result.reviewStatus());
        assertTrue(result.reviewSummary().contains("未执行"));
    }

    @Test
    void fullExecutorQueueFallsBackAndDoesNotFailUploadScheduling() {
        Executor full = task -> { throw new RejectedExecutionException("full"); };
        assertDoesNotThrow(() -> service(full).schedule(7, 31));
        assertTrue(saved().summary().contains("队列已满"));
        verifyNoInteractions(extractor);
    }

    @Test
    void deletedOrSupersededJobDoesNotReadContent() {
        when(repository.isCurrentAndActive(job)).thenReturn(false);
        service(Runnable::run).schedule(7, 31);
        verifyNoInteractions(deepseek, extractor);
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void repeatedRequestReusesPendingJobAndRenameForcesNewGeneration() {
        when(repository.start(7, 31, true, false)).thenReturn(null);
        when(repository.start(7, 31, true, true)).thenReturn(job);
        Executor executor = mock(Executor.class);
        FileAnalysisService service = service(executor);
        service.schedule(7, 31);
        verifyNoInteractions(executor);
        service.schedule(7, 31, true);
        verify(executor).execute(any());
    }

    @Test
    void disabledDefaultDoesNotProduceAnyReviewConclusion() {
        AnalysisJob disabled = DeepSeekAnalysisClientTest.job(false);
        when(settings.reviewEnabled()).thenReturn(false);
        when(repository.start(7, 31, false, false)).thenReturn(disabled);
        when(repository.find(7, 31, false)).thenReturn(pending);
        when(repository.isCurrentAndActive(disabled)).thenReturn(true);
        service(Runnable::run).schedule(7, 31);
        var captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(repository).complete(eq(disabled), captor.capture());
        assertEquals("DISABLED", captor.getValue().reviewStatus());
    }

    private FileAnalysisService service(Executor executor) {
        return new FileAnalysisService(repository, settings, deepseek, extractor, executor, manager);
    }

    private AnalysisResult saved() {
        var captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(repository).complete(eq(job), captor.capture());
        return captor.getValue();
    }
}
