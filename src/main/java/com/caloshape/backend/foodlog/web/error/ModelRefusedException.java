package com.caloshape.backend.foodlog.web.error;

import com.caloshape.backend.foodlog.model.ProviderRefuseReason;

public class ModelRefusedException extends RuntimeException {
    private final ProviderRefuseReason reason;

    public ModelRefusedException(ProviderRefuseReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProviderRefuseReason reason() {
        return reason;
    }
}