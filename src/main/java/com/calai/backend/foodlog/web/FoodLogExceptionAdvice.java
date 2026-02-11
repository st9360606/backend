package com.calai.backend.foodlog.web;

import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.controller.FoodLogController;
import com.calai.backend.foodlog.controller.FoodLogImageController;
import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import com.calai.backend.foodlog.dto.ModelRefusedResponse;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.FileNotFoundException;
import java.util.List;

@RestControllerAdvice(assignableTypes = {
        FoodLogController.class,
        FoodLogImageController.class
})
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

        return ResponseEntity.status(status).body(err(code, e, req));
    }

    /**
     * ✅ v1.2：Cooldown 固定回 429
     * - header: Retry-After = cooldownSeconds
     * - body: errorCode, nextAllowedAtUtc, cooldownSeconds, cooldownLevel, cooldownReason, suggestedTier
     */
    @ExceptionHandler(CooldownActiveException.class)
    public ResponseEntity<FoodLogErrorResponse> handleCooldown(CooldownActiveException e, HttpServletRequest req) {

        int seconds = Math.max(0, e.cooldownSeconds());
        String nextUtc = (e.nextAllowedAtUtc() == null) ? null : e.nextAllowedAtUtc().toString();
        String reason = (e.cooldownReason() == null) ? null : e.cooldownReason().name();

        // MVP：風控/超額/限流都建議 LOW（符合 v1.2）
        String suggestedTier = "MODEL_TIER_LOW";

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(new FoodLogErrorResponse(
                        "COOLDOWN_ACTIVE",
                        safeMsgOrCode(e, "COOLDOWN_ACTIVE"),
                        rid(req),
                        "RETRY_LATER",
                        seconds,         // retryAfterSec（舊欄位相容）
                        nextUtc,         // nextAllowedAtUtc
                        seconds,         // cooldownSeconds
                        e.cooldownLevel(),
                        reason,          // cooldownReason: OVER_QUOTA / ABUSE / RATE_LIMIT
                        suggestedTier
                ));
    }

    /**
     * ✅ 這裡改成 ResponseEntity<?>，避免 422 型別衝突
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e, HttpServletRequest req) {
        String code = norm(e.getMessage(), "ILLEGAL_STATE");

        // 保險：若有人仍丟 PROVIDER_REFUSED_* 也回 422
        ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(code);
        if (reason != null) {
            return handleModelRefused(new ModelRefusedException(reason, code), req);
        }

        HttpStatus status = switch (code) {
            case "IMAGE_OBJECT_KEY_MISSING" -> HttpStatus.CONFLICT;
            case "EMPTY_IMAGE" -> HttpStatus.BAD_REQUEST;
            case "PROVIDER_NOT_AVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(err(code, e, req));
    }

    /**
     * ✅ v1.2：Safety/Recitation/Harm -> HTTP 422 + MODEL_REFUSED
     */
    @ExceptionHandler(ModelRefusedException.class)
    public ResponseEntity<ModelRefusedResponse> handleModelRefused(ModelRefusedException ex, HttpServletRequest req) {
        String requestId = RequestIdFilter.getOrCreate(req);

        String userMessageKey = switch (ex.reason()) {
            case SAFETY, HARM_CATEGORY -> "PLEASE_PHOTO_FOOD_ONLY";
            case RECITATION -> "CONTENT_NOT_SUPPORTED_TRY_FOOD_PHOTO";
        };

        ModelRefusedResponse body = new ModelRefusedResponse(
                "MODEL_REFUSED",                       // ✅ errorCode（v1.2）
                ex.reason().name(),                    // refuseReason: SAFETY|RECITATION|HARM_CATEGORY
                userMessageKey,
                "We couldn't process this image. Please take a photo of food only (avoid faces, explicit content, or screenshots).",
                List.of("RETRY_WITH_FOOD_ONLY", "TRY_LABEL", "TRY_BARCODE"),
                requestId
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<FoodLogErrorResponse> handleReqInProgress(RequestInProgressException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()))
                .body(new FoodLogErrorResponse(
                        "REQUEST_IN_PROGRESS",
                        safeMsgOrCode(e, "REQUEST_IN_PROGRESS"),
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
                        safeMsgOrCode(e, "SUBSCRIPTION_REQUIRED"),
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
                        safeMsgOrCode(e, "QUOTA_EXCEEDED"),
                        rid(req),
                        e.clientAction(),
                        e.retryAfterSec()
                ));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<FoodLogErrorResponse> handleNotFound(FileNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("OBJECT_NOT_FOUND", e, req));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<FoodLogErrorResponse> handleSecurity(SecurityException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("INVALID_OBJECT_KEY", e, req));
    }

    @ExceptionHandler(TooManyInFlightException.class)
    public ResponseEntity<FoodLogErrorResponse> handleInFlight(TooManyInFlightException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()))
                .body(new FoodLogErrorResponse(
                        "TOO_MANY_IN_FLIGHT",
                        safeMsgOrCode(e, "TOO_MANY_IN_FLIGHT"),
                        rid(req),
                        e.clientAction(),
                        e.retryAfterSec()
                ));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<FoodLogErrorResponse> handleRateLimited(RateLimitedException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSec()))
                .body(new FoodLogErrorResponse(
                        "RATE_LIMITED",
                        safeMsgOrCode(e, "RATE_LIMITED"),
                        rid(req),
                        e.clientAction(),
                        e.retryAfterSec()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FoodLogErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err("INTERNAL_ERROR", e, req));
    }

    // ===== helpers =====

    private static FoodLogErrorResponse err(String code, Throwable e, HttpServletRequest req) {
        return new FoodLogErrorResponse(
                code,
                safeMsgOrCode(e, code),
                rid(req),
                null,
                null
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

    private static String safeMsgOrCode(Throwable t, String code) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) return code;
        return m;
    }
}
