package com.mengde.clouddrive.error;

/**
 * 同一资源已有上传操作在进行中，分布式锁未获取到（Day21）。对应 HTTP 409。
 *
 * <p>这里选择 tryLock(0) 立即失败而不是排队等待：上传是长耗时操作，
 * 让后来的请求挂在锁上只会堆积连接和线程，快速失败让客户端自己决定是否重试。</p>
 */
public final class UploadInProgressException extends RuntimeException {
    public UploadInProgressException(String resource) {
        super("another upload is currently modifying " + resource);
    }
}
