package com.mengde.clouddrive.web;

/**
 * 重命名条目的 HTTP 请求体。
 *
 * @param displayName 新显示名，不能为空白
 */
public record RenameRequest(String displayName) {
}
