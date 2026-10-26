package com.mengde.clouddrive.error;

/**
 * 注册时用户名已被占用（Day15）。对应 HTTP 409。
 */
public final class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String username) {
        super("username already exists: " + username);
    }
}
