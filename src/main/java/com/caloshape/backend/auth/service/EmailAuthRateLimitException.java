package com.caloshape.backend.auth.service;

public class EmailAuthRateLimitException extends RuntimeException {

    private final int retryAfterSec;

    public EmailAuthRateLimitException(int retryAfterSec) {
        super("TOO_MANY_ATTEMPTS");
        this.retryAfterSec = Math.max(1, retryAfterSec);
    }

    public int getRetryAfterSec() {
        return retryAfterSec;
    }
}
