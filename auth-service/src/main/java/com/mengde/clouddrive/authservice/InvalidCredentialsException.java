package com.mengde.clouddrive.authservice;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("invalid credentials"); }
}
