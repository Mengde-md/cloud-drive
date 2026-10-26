package com.mengde.clouddrive.application;

import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;

/** Best-effort filesystem cleanup must never replace the operation's original result. */
final class UploadCleanup {
    private UploadCleanup() { }

    @FunctionalInterface
    interface Action {
        void run() throws IOException;
    }

    static void run(Action action, Throwable original, String description, Logger log) {
        try {
            action.run();
        } catch (IOException | RuntimeException cleanupFailure) {
            if (original != null && original != cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
            log.warn("upload cleanup failed action={} originalFailureType={}", description,
                    original == null ? "none" : original.getClass().getSimpleName(), cleanupFailure);
        }
    }

    /** Keep retryable parts until the completed session and file metadata really commit. */
    static void afterCommit(Action action, String description, Logger log) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run(action, null, description, log);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(action, null, description, log);
            }
        });
    }

    /** A commit-time database failure happens after the service method's catch blocks. */
    static void onRollback(Action action, String description, Logger log) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // UNKNOWN is not proof of rollback: deleting then could destroy committed data.
                if (status == STATUS_ROLLED_BACK) {
                    run(action, null, description, log);
                }
            }
        });
    }
}
