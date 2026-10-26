package com.mengde.clouddrive.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 重命名请求体（Day06）。
 */
public record RenameRequest(
        @NotBlank(message = "displayName must not be blank") String displayName) {
}
