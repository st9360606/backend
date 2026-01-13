package com.calai.backend.foodlog.web;

public class SubscriptionRequiredException extends RuntimeException {
    private final String clientAction;

    public SubscriptionRequiredException(String message, String clientAction) {
        super(message);
        this.clientAction = clientAction;
    }

    public String clientAction() { return clientAction; }
}
