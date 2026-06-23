package com.caloshape.backend.foodlog.web.error;

public class RequestInProgressException extends RuntimeException {
    private final int retryAfterSec;

    public RequestInProgressException(String message, int retryAfterSec) {
        super(message);
        this.retryAfterSec = retryAfterSec;
    }

    public int retryAfterSec() { return retryAfterSec; }
}
