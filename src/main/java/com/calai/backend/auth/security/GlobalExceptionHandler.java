package com.calai.backend.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex, HttpServletRequest req) {
        String rid = UUID.randomUUID().toString();
        log.error("RID={} {} {} failed", rid, req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(Map.of(
                "requestId", rid,
                "error", ex.getClass().getSimpleName(),
                "message", ex.getMessage()
        ));
    }
}
