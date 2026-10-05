package com.mengde.clouddrive.ai;

public final class AiFailure extends RuntimeException {
    private final int status;
    public AiFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
