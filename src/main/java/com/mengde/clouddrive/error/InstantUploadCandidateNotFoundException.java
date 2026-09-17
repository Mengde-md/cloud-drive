package com.mengde.clouddrive.error;

public final class InstantUploadCandidateNotFoundException extends RuntimeException {
    public InstantUploadCandidateNotFoundException() { super("no reusable document belongs to the current user"); }
}
