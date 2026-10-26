package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.persistence.StoredDocumentRow;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileAnalysisTriggerTest {
    @Test
    void onlyCommittedUploadsAreScheduledAndSchedulingFailureDoesNotFailCommit() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var trigger = context.getBean(FileAnalysisTrigger.class);
            var analysis = context.getBean(FileAnalysisService.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var row = document();
            tx.executeWithoutResult(status -> {
                trigger.uploaded(row);
                verifyNoInteractions(analysis);
            });
            verify(analysis).schedule(7, 31, false);
            reset(analysis);
            tx.executeWithoutResult(status -> {
                trigger.uploaded(row);
                status.setRollbackOnly();
            });
            verifyNoInteractions(analysis);
            when(analysis.schedule(7, 31, false)).thenThrow(new IllegalStateException("unavailable"));
            assertDoesNotThrow(() -> tx.executeWithoutResult(status -> trigger.uploaded(row)));
        }
    }

    @Test
    void failureToPublishCannotFailSuccessfulUpload() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("unavailable")).when(events).publishEvent(any(Object.class));
        var trigger = new FileAnalysisTrigger(events, mock(FileAnalysisService.class));
        assertDoesNotThrow(() -> trigger.uploaded(document()));
    }

    private StoredDocumentRow document() {
        var row = new StoredDocumentRow();
        row.setId(31L);
        row.setOwnerId(7L);
        return row;
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean FileAnalysisService analysis() { return mock(FileAnalysisService.class); }
        @Bean FileAnalysisTrigger trigger(ApplicationEventPublisher events, FileAnalysisService service) {
            return new FileAnalysisTrigger(events, service);
        }
        @Bean PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
    }
}
