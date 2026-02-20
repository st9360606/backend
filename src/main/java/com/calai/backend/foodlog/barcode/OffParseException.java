package com.calai.backend.foodlog.barcode;

import lombok.Getter;

@Getter
public class OffParseException extends RuntimeException {

    private final String code;
    private final String bodySnippet;

    public OffParseException(String code, String message, String bodySnippet, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.bodySnippet = bodySnippet;
    }

}
