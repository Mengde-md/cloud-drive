package com.mengde.clouddrive.web;

/**
 * 统一错误响应体。所有非 2xx 响应都使用该结构，
 * 让调用方能用同一份契约解析 4xx 与 5xx。
 *
 * @param status  HTTP 状态码
 * @param error   状态码短语
 * @param message 面向调用方的错误说明
 */
public record ApiError(int status, String error, String message) {
}
