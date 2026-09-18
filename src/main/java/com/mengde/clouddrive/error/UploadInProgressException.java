package com.mengde.clouddrive.error;

public final class UploadInProgressException extends RuntimeException {
    public UploadInProgressException(String resource) {
        super("another upload request is already changing " + resource);
    }
}
