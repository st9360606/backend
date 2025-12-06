package com.calai.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.DateTimeException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 統一把常見例外轉成「可預期」的 HTTP 狀態碼與錯誤格式：
 * - 400：參數格式錯 / timezone / date parse / IllegalArgument
 * - 404：找不到資源（PROFILE_NOT_FOUND / NoSuchElementException）
 * - 422：語意錯誤（例如缺必要欄位 WEIGHT_REQUIRED）
 * - 500：其他未預期錯誤
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    // ===== 400 Bad Request =====

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(err("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * Bean Validation（@Valid）失敗：例如 @NotBlank / @Min 等
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().isEmpty()
                ? "VALIDATION_FAILED"
                : ex.getBindingResult().getFieldErrors().get(0).getField()
                + " " + ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(err("VALIDATION_FAILED", msg));
    }

    // ===== 404 / 422 / 500 from IllegalStateException codes =====

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        String code = (ex.getMessage() == null || ex.getMessage().isBlank())
                ? "ILLEGAL_STATE"
                : ex.getMessage().trim();

        HttpStatus status = switch (code) {
            case "PROFILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "WEIGHT_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(err(code, ex.getMessage()));
    }

    // ===== 404 Not Found =====

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuch(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(err("NOT_FOUND", ex.getMessage()));
    }

    // ===== 500 Fallback =====

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception ex) {
        // 上線可改成不回 message，避免洩漏內部資訊；log 例外即可
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(err("INTERNAL_ERROR", ex.getMessage()));
    }

    private static Map<String, Object> err(String code, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        if (message != null && !message.isBlank()) m.put("message", message);
        return m;
    }
}
