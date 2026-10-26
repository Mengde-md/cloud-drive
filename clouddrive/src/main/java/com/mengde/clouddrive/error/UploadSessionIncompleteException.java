package com.mengde.clouddrive.error;

/**
 * 上传会话状态不满足要求（Day18）：缺片、乱序或会话已结束。对应 HTTP 409。
 */
public final class UploadSessionIncompleteException extends RuntimeException {
    public UploadSessionIncompleteException(String message) {
        super(message);
    }
}
