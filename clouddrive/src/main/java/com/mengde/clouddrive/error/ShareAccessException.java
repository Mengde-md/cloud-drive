package com.mengde.clouddrive.error;

/**
 * 分享访问失败（Day19）：token 不存在、已过期或提取码错误。
 *
 * <p>三种原因合并成同一个异常并统一映射为 404，是有意的：
 * 如果分别返回"分享不存在"和"提取码错误"，攻击者就能用响应差异先枚举出哪些 token 有效。</p>
 */
public final class ShareAccessException extends RuntimeException {
    public ShareAccessException() {
        super("share not found or passcode is invalid");
    }
}
