package com.calai.backend.foodlog.barcode.openfoodfacts.error;

import lombok.Getter;

@Getter
public class OffHttpException extends RuntimeException {

    private final int status;
    private final String bodySnippet;

    public OffHttpException(int status, String message, String bodySnippet) {
        super(message);
        this.status = status;
        this.bodySnippet = bodySnippet;
    }

}
