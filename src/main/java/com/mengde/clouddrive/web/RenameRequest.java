package com.mengde.clouddrive.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 重命名条目的 HTTP 请求体。
 *
 * @param displayName 新显示名，不能为空白
 */
public record RenameRequest(@NotBlank(message = "displayName must not be blank") String displayName) {
}
