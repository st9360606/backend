package com.calai.backend.foodlog.web;

import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.controller.FoodLogController;
import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.FileNotFoundException;

@RestControllerAdvice(assignableTypes = FoodLogController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FoodLogExceptionAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<FoodLogErrorResponse> handleIllegalArg(IllegalArgumentException e, HttpServletRequest req) {
        String code = norm(e.getMessage(), "BAD_REQUEST");
        HttpStatus status = switch (code) {
            case "FOOD_LOG_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FOOD_LOG_DELETED" -> HttpStatus.GONE;

            case "FOOD_LOG_NOT_READY",
                 "FOOD_LOG_FAILED",
                 "FOOD_LOG_NOT_SAVABLE",
                 "DATE_RANGE_REQUIRED",
                 "DATE_RANGE_INVALID",
                 "FOOD_LOG_NOT_EDITABLE",
                 "FIELD_KEY_INVALID",
                 "OVERRIDE_VALUE_INVALID",
                 "PAGE_SIZE_TOO_LARGE" -> HttpStatus.CONFLICT;

            case "FILE_REQUIRED",
                 "FILE_TOO_LARGE",
                 "UNSUPPORTED_IMAGE_FORMAT",
                 "UNSUPPORTED_CONTENT_TYPE" -> HttpStatus.BAD_REQUEST;

            default -> HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity.status(status)
                .body(err(code, e, req)); // ✅ 補齊 5 欄位
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<FoodLogErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest req) {
        String code = norm(e.getMessage(), "ILLEGAL_STATE");
        HttpStatus status = switch (code) {
            case "IMAGE_OBJECT_KEY_MISSING" -> HttpStatus.CONFLICT;
            case "EMPTY_IMAGE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status)
                .body(err(code, e, req)); // ✅ 補齊 5 欄位
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<FoodLogErrorResponse> handleReqInProgress(RequestInProgressException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()))
                .body(new FoodLogErrorResponse(
                        "REQUEST_IN_PROGRESS",
                        safeMsg(e),
                        rid(req),
                        "RETRY_LATER",
                        e.retryAfterSec()
                ));
    }

    @ExceptionHandler(SubscriptionRequiredException.class)
    public ResponseEntity<FoodLogErrorResponse> handleSubRequired(SubscriptionRequiredException e, HttpServletRequest req) {
        return ResponseEntity.status(402)
                .body(new FoodLogErrorResponse(
                        "SUBSCRIPTION_REQUIRED",
                        safeMsg(e),
                        rid(req),
                        e.clientAction(),
                        null
                ));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<FoodLogErrorResponse> handleQuota(QuotaExceededException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()))
                .body(new FoodLogErrorResponse(
                        "QUOTA_EXCEEDED",
                        safeMsg(e),
                        rid(req),
                        e.clientAction(),
                        e.retryAfterSec()
                ));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<FoodLogErrorResponse> handleNotFound(FileNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(err("OBJECT_NOT_FOUND", e, req)); // ✅ 補齊 5 欄位
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<FoodLogErrorResponse> handleSecurity(SecurityException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(err("INVALID_OBJECT_KEY", e, req)); // ✅ 補齊 5 欄位
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FoodLogErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(err("INTERNAL_ERROR", e, req)); // ✅ 補齊 5 欄位
    }

    // ===== helpers =====

    private static FoodLogErrorResponse err(String code, Throwable e, HttpServletRequest req) {
        return new FoodLogErrorResponse(
                code,
                safeMsg(e),
                rid(req),
                null,  // clientAction
                null   // retryAfterSec
        );
    }

    private static String rid(HttpServletRequest req) {
        return RequestIdFilter.getOrCreate(req);
    }

    private static String norm(String msg, String fallback) {
        if (msg == null) return fallback;
        String c = msg.trim();
        return c.isEmpty() ? fallback : c;
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? null : m;
    }
}
