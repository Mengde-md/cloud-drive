package com.mengde.clouddrive.authservice;

public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException() { super("username already exists"); }
}
