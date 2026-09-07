package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DriveEntry;

import java.util.List;

/**
 * The synchronous boundary that actually writes one immutable snapshot.
 *
 * <p>Day05 deliberately keeps the write implementation outside the executor.
 * The executor owns admission, duplicate coalescing and lifecycle; the consumer
 * owns the IO operation inherited from Day04.</p>
 */
@FunctionalInterface
public interface SnapshotConsumer {

    void accept(List<? extends DriveEntry> entries) throws Exception;
}
