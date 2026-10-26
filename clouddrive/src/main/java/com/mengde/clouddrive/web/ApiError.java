package com.mengde.clouddrive.web;

/**
 * 统一错误响应体（Day06）。
 *
 * <p>所有非 2xx 响应都用这一种结构：调用方只需要写一套解析逻辑，
 * 而不是对 400、404、409、500 各猜一种格式。</p>
 */
public record ApiError(int status, String error, String message) {
}
