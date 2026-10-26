package com.mengde.clouddrive.error;

/**
 * 同一用户、同一父目录下出现重名（Day16）。对应 HTTP 409。
 *
 * <p>真正兜底的是数据库唯一键，而不是先 SELECT 再 INSERT 的检查：
 * 并发下两个请求可能都查到"不存在"然后一起插入。
 * 因此代码的做法是直接插入、捕获 DuplicateKeyException 再翻译成这个业务异常。</p>
 */
public final class DuplicateEntryNameException extends RuntimeException {
    public DuplicateEntryNameException(String displayName) {
        super("entry name already exists in the same directory: " + displayName);
    }
}
