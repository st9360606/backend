package com.calai.backend.auth.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.Map;

@RestControllerAdvice
public class WebExceptionAdvice {

    // ✅ 針對 Google 驗證等拋出的 IllegalArgumentException，轉成 401 並給可讀 code
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        String msg = (e.getMessage() == null) ? "Illegal argument" : e.getMessage();
        String lower = msg.toLowerCase();

        // 關鍵字判斷可依你的訊息調整（audience mismatch / invalid id token / google）
        if (lower.contains("google") || lower.contains("id token")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "INVALID_GOOGLE_ID_TOKEN",
                    "message", msg
            ));
        }
        // 其它非法參數 → 400
        return ResponseEntity.badRequest().body(Map.of(
                "code", "BAD_REQUEST",
                "message", msg
        ));
    }

    // ✅ 延續你服務中使用的 ResponseStatusException（例如 Email 驗證）→ 保留原狀態碼，但統一 JSON 形狀
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRSE(ResponseStatusException e) {
        HttpStatus status = (HttpStatus) e.getStatusCode();
        String msg = (e.getReason() == null) ? status.getReasonPhrase() : e.getReason();
        // 依常見錯誤映射 code（可再細分）
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

    // ✅ 常見反序列化/參數錯誤 → 400
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

    // ✅ 兜底：未處理的例外 → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Unexpected error"
        ));
    }
}
