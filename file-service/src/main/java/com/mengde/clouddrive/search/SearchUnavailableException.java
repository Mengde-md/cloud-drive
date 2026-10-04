package com.mengde.clouddrive.search;

public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String reason) { super(reason); }
    public SearchUnavailableException(String reason, Throwable cause) { super(reason, cause); }
}
