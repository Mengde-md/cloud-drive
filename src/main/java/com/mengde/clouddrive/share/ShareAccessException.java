package com.mengde.clouddrive.share;
public class ShareAccessException extends RuntimeException { public ShareAccessException() { super("share token, passcode, or expiry is invalid"); } }
