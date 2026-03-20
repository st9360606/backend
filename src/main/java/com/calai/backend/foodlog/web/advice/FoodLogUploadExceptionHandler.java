package com.calai.backend.foodlog.web.advice;

import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import com.calai.backend.foodlog.model.ClientAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 統一處理 foodlog 上傳相關例外
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class FoodLogUploadExceptionHandler {

    /**
     * 單檔 / request 超過 multipart 限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<FoodLogErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex,
            HttpServletRequest req
    ) {
        return payloadTooLarge(req);
    }

    /**
     * 某些情況 multipart 解析失敗不會直接丟 MaxUploadSizeExceededException，
     * 而是包成 MultipartException；這裡做保底。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<FoodLogErrorResponse> handleMultipartException(
            MultipartException ex,
            HttpServletRequest req
    ) {
        Throwable cause = ex.getCause();
        if (cause instanceof MaxUploadSizeExceededException) {
            return payloadTooLarge(req);
        }

        String requestId = RequestIdFilter.getOrCreate(req);
        FoodLogErrorResponse body = new FoodLogErrorResponse(
                "UNSUPPORTED_IMAGE_FORMAT",
                "Multipart parsing failed",
                requestId,
                ClientAction.RETAKE_PHOTO.name(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(body);
    }

    /**
     * 缺少 multipart part，例如前端沒有帶 file
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<FoodLogErrorResponse> handleMissingServletRequestPart(
            MissingServletRequestPartException ex,
            HttpServletRequest req
    ) {
        String requestId = RequestIdFilter.getOrCreate(req);

        FoodLogErrorResponse body = new FoodLogErrorResponse(
                "FILE_REQUIRED",
                "Required multipart file part is missing",
                requestId,
                ClientAction.RETAKE_PHOTO.name(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(body);
    }

    private ResponseEntity<FoodLogErrorResponse> payloadTooLarge(HttpServletRequest req) {
        String requestId = RequestIdFilter.getOrCreate(req);

        FoodLogErrorResponse body = new FoodLogErrorResponse(
                "IMAGE_TOO_LARGE",
                "Image exceeds upload size limit",
                requestId,
                ClientAction.RETAKE_PHOTO.name(),
                null
        );

        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE) // 413
                .body(body);
    }
}
