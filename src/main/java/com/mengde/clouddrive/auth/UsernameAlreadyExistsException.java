package com.mengde.clouddrive.auth;
public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException() { super("username already exists"); }
}
