package com.mengde.clouddrive.error;

/**
 * 秒传候选未命中（Day17）。对应 HTTP 404。
 *
 * <p>注意语义边界：候选只在"当前用户自己已有的文件"里找。
 * 别人的文件即使 MD5 相同也不算命中，否则拿到一个 MD5 就等于拿到别人的文件内容。</p>
 */
public final class InstantUploadCandidateNotFoundException extends RuntimeException {
    public InstantUploadCandidateNotFoundException() {
        super("no reusable document belongs to the current user");
    }
}
