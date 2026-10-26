package com.mengde.clouddrive.error;

/**
 * 用户名或口令不正确（Day15）。对应 HTTP 401。
 * 不区分"用户不存在"和"密码错误"，避免用户名枚举。
 */
public final class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("username or password is incorrect");
    }
}
