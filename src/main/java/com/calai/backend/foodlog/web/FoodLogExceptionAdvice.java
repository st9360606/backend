package com.calai.backend.foodlog.web;

import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.controller.FoodLogController;
import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

            case "FILE_REQUIRED",
                 "FILE_TOO_LARGE",
                 "UNSUPPORTED_IMAGE_FORMAT",
                 "UNSUPPORTED_CONTENT_TYPE" -> HttpStatus.BAD_REQUEST;

            default -> HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity.status(status)
                .body(new FoodLogErrorResponse(code, safeMsg(e), rid(req)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<FoodLogErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest req) {
        String code = norm(e.getMessage(), "ILLEGAL_STATE");
        HttpStatus status = switch (code) {
            case "IMAGE_OBJECT_KEY_MISSING" -> HttpStatus.CONFLICT; // 資料狀態不一致
            case "EMPTY_IMAGE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status)
                .body(new FoodLogErrorResponse(code, safeMsg(e), rid(req)));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<FoodLogErrorResponse> handleNotFound(FileNotFoundException e, HttpServletRequest req) {
        // 例如：LocalDiskStorageService.open() 的 OBJECT_NOT_FOUND
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new FoodLogErrorResponse("OBJECT_NOT_FOUND", safeMsg(e), rid(req)));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<FoodLogErrorResponse> handleSecurity(SecurityException e, HttpServletRequest req) {
        // 例如：objectKey traversal 被擋
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new FoodLogErrorResponse("INVALID_OBJECT_KEY", safeMsg(e), rid(req)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FoodLogErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
        // 上線可改成不要回 message，避免洩漏內部資訊
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new FoodLogErrorResponse("INTERNAL_ERROR", safeMsg(e), rid(req)));
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
