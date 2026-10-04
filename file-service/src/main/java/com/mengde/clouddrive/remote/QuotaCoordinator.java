package com.mengde.clouddrive.remote;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.QuotaOperationMapper;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class QuotaCoordinator {
    private static final Logger log = LoggerFactory.getLogger(QuotaCoordinator.class);
    private final QuotaGateway gateway;
    private final QuotaOperationMapper operations;
    private final DriveEntryRepository entries;
    private final UploadCoordination locks;
    private final ObjectStorage storage;
    private final TransactionTemplate transaction;

    public QuotaCoordinator(QuotaGateway gateway, QuotaOperationMapper operations,
                            DriveEntryRepository entries, UploadCoordination locks,
                            ObjectStorage storage, PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.operations = operations;
        this.entries = entries;
        this.locks = locks;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(long owner, long amount, String newStorageKey, Supplier<T> write) {
        boolean prepared = false;
        try (var ignored = locks.acquireOwnerUploadLock(owner)) {
            reconcile(owner);
            String id = UUID.randomUUID().toString();
            transaction.executeWithoutResult(status -> operations.prepare(id, owner, newStorageKey));
            prepared = true;
            try {
                gateway.reserve(new QuotaGateway.Reservation(owner, id), amount, entries.usedBytesByOwner(owner));
                T result = transaction.execute(status -> {
                    var operation = operations.lock(id);
                    if (operation == null || !"PREPARED".equals(operation.phase())) {
                        throw new QuotaGateway.QuotaUnavailableException();
                    }
                    T value = write.get();
                    if (operations.committed(id) != 1) throw new IllegalStateException("quota operation transition failed");
                    return value;
                });
                // The metadata and COMMIT_PENDING marker have committed atomically. Never release them on timeout.
                try { reconcile(owner); }
                catch (RuntimeException deferred) {
                    log.warn("event=quota_finalize_pending traceId={} phase=COMMIT_PENDING", org.slf4j.MDC.get("traceId"));
                }
                return result;
            } catch (RuntimeException failure) {
                try { reconcile(owner); }
                catch (RuntimeException deferred) { failure.addSuppressed(deferred); }
                throw failure;
            }
        } catch (RuntimeException failure) {
            if (!prepared && newStorageKey != null) {
                try { storage.delete(newStorageKey); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public <T> T mutate(long owner, Supplier<T> write) {
        try (var ignored = locks.acquireOwnerUploadLock(owner)) {
            reconcile(owner);
            return transaction.execute(status -> write.get());
        }
    }

    // Row locking prevents a resumed Redis-lock holder from resurrecting an already cancelled operation.
    private void reconcile(long owner) {
        for (var candidate : operations.pending(owner)) {
            // Persist cancellation before irreversible remote or filesystem effects.
            transaction.executeWithoutResult(status -> {
                var operation = operations.lock(candidate.id());
                if (operation != null && "PREPARED".equals(operation.phase()) && operations.cancelling(operation.id()) != 1) {
                    throw new IllegalStateException("quota cancellation transition failed");
                }
            });
            transaction.executeWithoutResult(status -> {
                var operation = operations.lock(candidate.id());
                if (operation == null || "DONE".equals(operation.phase())) return;
                var reservation = new QuotaGateway.Reservation(owner, operation.id());
                if ("COMMIT_PENDING".equals(operation.phase())) {
                    gateway.commit(reservation);
                } else {
                    gateway.release(reservation);
                    if (operation.storageKey() != null) {
                        try { storage.delete(operation.storageKey()); }
                        catch (IOException failure) { throw new QuotaGateway.QuotaUnavailableException(failure); }
                    }
                }
                if (operations.done(operation.id(), operation.phase()) != 1) {
                    throw new IllegalStateException("quota operation finalization failed");
                }
            });
        }
    }
}
