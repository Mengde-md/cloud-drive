package com.base.common.exception;

import lombok.Getter;

@Getter
public class SystemException extends RuntimeException {

    private final int code;

    public SystemException(String message) {
        super(message);
        this.code = 500;
    }

    public SystemException(int code, String message) {
        super(message);
        this.code = code;
    }
}
