package com.mengde.clouddrive.error;

/** Registration uniqueness conflicts roll back valid-code consumption, unlike invalid-code rejections. */
public final class RegistrationConflictException extends RuntimeException {
    public RegistrationConflictException(Throwable cause) {
        super("用户名或邮箱已注册", cause);
    }
}
