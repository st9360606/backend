package com.caloshape.backend.entitlement.web;

import com.caloshape.backend.common.web.RequestIdFilter;
import com.caloshape.backend.entitlement.controller.EntitlementController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(EntitlementExceptionAdvice.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(
            IllegalStateException exception,
            HttpServletRequest req
    ) {
        String code = normalizeCode(exception.getMessage());
        if (!PURCHASE_TOKEN_ALREADY_BOUND.equals(code)) {
            String requestId = RequestIdFilter.getOrCreate(req);
            log.error(
                    "Unhandled entitlement request failure method={} path={} exceptionType={}",
                    req.getMethod(),
                    req.getRequestURI(),
                    exception.getClass().getName()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", "INTERNAL_ERROR",
                    "message", "Unexpected error",
                    "requestId", requestId
            ));
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
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
