package com.caloshape.backend.entitlement.web;

import com.caloshape.backend.entitlement.controller.EntitlementController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = EntitlementController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EntitlementExceptionAdvice {

    static final String PURCHASE_TOKEN_ALREADY_BOUND = "PURCHASE_TOKEN_ALREADY_BOUND";

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException exception) {
        String code = normalizeCode(exception.getMessage());
        HttpStatus status = PURCHASE_TOKEN_ALREADY_BOUND.equals(code)
                ? HttpStatus.CONFLICT
                : HttpStatus.INTERNAL_SERVER_ERROR;

        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", code
        ));
    }

    private static String normalizeCode(String message) {
        if (message == null || message.isBlank()) {
            return "ILLEGAL_STATE";
        }
        return message.trim();
    }
}
