package com.mengde.clouddrive.error;

/**
 * 同一 id 的条目已存在（Day03）。对应 HTTP 409。
 */
public final class DuplicateEntryException extends RuntimeException {
    public DuplicateEntryException(long entryId) {
        super("entry already exists: " + entryId);
    }
}
