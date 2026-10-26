package com.mengde.clouddrive.error;

/**
 * 条目不存在（Day03）。
 *
 * <p>为什么不返回 null：null 会把"没找到"这件事推迟到调用方某一行 NPE 才爆出来，
 * 现场已经离开真正出错的地方。抛业务异常能在边界立刻说明原因，
 * Web 层再把它统一映射成 404。</p>
 */
public final class EntryNotFoundException extends RuntimeException {
    public EntryNotFoundException(long entryId) {
        super("entry not found: " + entryId);
    }
}
