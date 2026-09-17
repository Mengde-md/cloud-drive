package com.mengde.clouddrive.error;

public final class DirectoryNotEmptyException extends RuntimeException {
    public DirectoryNotEmptyException(long id) {
        super("directory %d still contains child entries".formatted(id));
    }
}
