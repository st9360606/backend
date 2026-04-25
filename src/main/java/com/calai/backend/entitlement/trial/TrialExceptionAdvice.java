package com.calai.backend.entitlement.trial;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "com.calai.backend.entitlement.trial")
public class TrialExceptionAdvice {

    @ExceptionHandler(TrialNotEligibleException.class)
    public ResponseEntity<Map<String, Object>> handleTrialNotEligible(TrialNotEligibleException ex) {
        String code = normalizeCode(ex.getMessage());

        HttpStatus status = switch (code) {
            case "DEVICE_ID_REQUIRED",
                 "EMAIL_REQUIRED",
                 "USER_NOT_FOUND",
                 "TRIAL_HASH_SECRET_NOT_CONFIGURED" -> HttpStatus.BAD_REQUEST;

            case "EMAIL_ALREADY_USED",
                 "DEVICE_ALREADY_USED",
                 "TRIAL_ALREADY_USED",
                 "ALREADY_PREMIUM" -> HttpStatus.CONFLICT;

            default -> HttpStatus.CONFLICT;
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", code);

        return ResponseEntity.status(status).body(body);
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) return "TRIAL_NOT_ELIGIBLE";
        return raw.trim();
    }
}
