package com.caloshape.backend.auth.web;

import com.caloshape.backend.auth.service.EmailAuthRateLimitException;
import com.caloshape.backend.auth.service.EmailAuthUnavailableException;
import com.caloshape.backend.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.caloshape.backend.auth")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionAdvice.class);

    @ExceptionHandler(EmailAuthRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(EmailAuthRateLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSec()))
                .body(Map.of(
                        "code", "TOO_MANY_ATTEMPTS",
                        "message", "Too many attempts. Please try again later."
                ));
    }

    @ExceptionHandler(EmailAuthUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAuthUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "AUTH_TEMPORARILY_UNAVAILABLE",
                "message", "Authentication is temporarily unavailable. Please try again later."
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        String msg = (e.getMessage() == null) ? "Illegal argument" : e.getMessage();
        String lower = msg.toLowerCase();

        if (lower.contains("google") || lower.contains("id token")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "INVALID_GOOGLE_ID_TOKEN",
                    "message", msg
            ));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "code", "BAD_REQUEST",
                "message", msg
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRSE(
            ResponseStatusException e,
            HttpServletRequest req
    ) {
        HttpStatus status = (HttpStatus) e.getStatusCode();
        if (status.is5xxServerError()) {
            return handleGeneric(e, req);
        }
        String msg = (e.getReason() == null) ? status.getReasonPhrase() : e.getReason();
        String code = switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            default -> "ERROR";
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", msg
        ));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "BAD_REQUEST",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception e,
            HttpServletRequest req
    ) {
        String requestId = RequestIdFilter.getOrCreate(req);
        log.error(
                "Unhandled auth request failure method={} path={} exceptionType={}",
                req.getMethod(),
                req.getRequestURI(),
                e.getClass().getName()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Unexpected error",
                "requestId", requestId
        ));
    }
}
