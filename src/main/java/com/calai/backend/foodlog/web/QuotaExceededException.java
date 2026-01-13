package com.calai.backend.foodlog.web;

public class QuotaExceededException extends RuntimeException {
    private final int retryAfterSec;
    private final String clientAction;

    public QuotaExceededException(String message, int retryAfterSec, String clientAction) {
        super(message);
        this.retryAfterSec = retryAfterSec;
        this.clientAction = clientAction;
    }

    public int retryAfterSec() { return retryAfterSec; }
    public String clientAction() { return clientAction; }
}
