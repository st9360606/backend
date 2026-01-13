package com.calai.backend.foodlog.web;

public class RequestInProgressException extends RuntimeException {
    private final int retryAfterSec;

    public RequestInProgressException(String message, int retryAfterSec) {
        super(message);
        this.retryAfterSec = retryAfterSec;
    }

    public int retryAfterSec() { return retryAfterSec; }
}
