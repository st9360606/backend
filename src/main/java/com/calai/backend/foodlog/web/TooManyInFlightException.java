package com.calai.backend.foodlog.web;

public class TooManyInFlightException extends RuntimeException {

    private final int retryAfterSec;
    private final String clientAction;

    public TooManyInFlightException(String message, int retryAfterSec, String clientAction) {
        super(message);
        this.retryAfterSec = retryAfterSec;
        this.clientAction = clientAction;
    }

    public int retryAfterSec() { return retryAfterSec; }
    public String clientAction() { return clientAction; }
}
