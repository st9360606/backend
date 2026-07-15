package com.caloshape.backend.auth.web;

import com.caloshape.backend.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.caloshape.backend.auth")
public class AuthGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex, HttpServletRequest req) {
        String rid = RequestIdFilter.getOrCreate(req);
        log.error(
                "Unhandled auth request failure method={} path={} exceptionType={}",
                req.getMethod(),
                req.getRequestURI(),
                ex.getClass().getName()
        );
        return ResponseEntity.status(500).body(Map.of(
                "requestId", rid,
                "code", "INTERNAL_ERROR",
                "message", "Unexpected error"
        ));
    }
}
