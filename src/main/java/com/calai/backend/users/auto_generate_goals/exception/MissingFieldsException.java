package com.calai.backend.users.auto_generate_goals.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class MissingFieldsException extends RuntimeException {
    private final List<String> missingFields;

    public MissingFieldsException(List<String> missingFields) {
        super("Missing fields: " + String.join(",", missingFields));
        this.missingFields = missingFields;
    }

}
