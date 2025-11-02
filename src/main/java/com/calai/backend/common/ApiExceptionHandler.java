package com.calai.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,Object>> handleIllegalState(IllegalStateException ex) {
        String code = ex.getMessage() == null ? "ILLEGAL_STATE" : ex.getMessage();
        int status = switch (code) {
            case "PROFILE_NOT_FOUND" -> 404;
            case "WEIGHT_REQUIRED"   -> 422;
            default -> 500;
        };
        return ResponseEntity.status(status).body(Map.of("code", code));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuch(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", 404, "error", "NOT_FOUND"));
    }
}
