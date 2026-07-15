package com.caloshape.backend.auth.service;

public class EmailAuthUnavailableException extends RuntimeException {

    public EmailAuthUnavailableException(Throwable cause) {
        super("AUTH_TEMPORARILY_UNAVAILABLE", cause);
    }
}
