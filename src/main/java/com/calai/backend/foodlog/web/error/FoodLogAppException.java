package com.calai.backend.foodlog.web.error;

import com.calai.backend.foodlog.model.FoodLogErrorCode;

public class FoodLogAppException extends RuntimeException {

    private final FoodLogErrorCode errorCode;

    public FoodLogAppException(FoodLogErrorCode errorCode) {
        super(errorCode.code());
        this.errorCode = errorCode;
    }

    public FoodLogAppException(FoodLogErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FoodLogErrorCode getErrorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.code();
    }
}