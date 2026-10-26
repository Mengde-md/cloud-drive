package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.persistence.StoredDocumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FileAnalysisTrigger {
    private static final Logger log = LoggerFactory.getLogger(FileAnalysisTrigger.class);
    private final ApplicationEventPublisher events;
    private final FileAnalysisService analysis;

    public FileAnalysisTrigger(ApplicationEventPublisher events, FileAnalysisService analysis) {
        this.events = events;
        this.analysis = analysis;
    }

    public void uploaded(StoredDocumentRow document) {
        try { events.publishEvent(new Uploaded(document.getOwnerId(), document.getId(), false)); }
        catch (RuntimeException failure) { log.warn("unable to register file analysis entryId={}", document.getId()); }
    }

    public void renamed(long ownerId, long entryId) {
        try { events.publishEvent(new Uploaded(ownerId, entryId, true)); }
        catch (RuntimeException failure) { log.warn("unable to register renamed file analysis entryId={}", entryId); }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCommit(Uploaded event) {
        try { analysis.schedule(event.ownerId(), event.entryId(), event.force()); }
        catch (RuntimeException failure) {
            // Upload has already committed; unavailable AI/queue/database must not undo that success.
            log.warn("unable to schedule file analysis entryId={} failureType={}", event.entryId(), failure.getClass().getSimpleName());
        }
    }

    public record Uploaded(long ownerId, long entryId, boolean force) { }
}
