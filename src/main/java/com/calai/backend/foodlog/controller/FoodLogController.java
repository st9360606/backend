package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.barcode.OpenFoodFactsLang;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.dto.FoodLogOverrideRequest;
import com.calai.backend.foodlog.service.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Tag(name = "FoodLog", description = "Food log (photo/album) + override/save/history")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/food-logs")
public class FoodLogController {

    private final AuthContext auth;
    private final FoodLogService service;
    private final FoodLogDeleteService deleteService;
    private final FoodLogHistoryService historyService;
    private final FoodLogOverrideService overrideService;

    @PostMapping(value = "/album", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FoodLogEnvelope album(
            @RequestHeader(value = "X-Client-Timezone", required = false) String clientTz,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest req
    ) throws Exception {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.createAlbum(uid, clientTz, deviceId, file, requestId);
    }

    /**
     * ✅ S4-08：相機拍照入口（multipart）
     * deviceCapturedAtUtc：App 端可用 Instant.now().toString() 傳上來（可選）
     */
    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FoodLogEnvelope photo(
            @RequestHeader(value = "X-Client-Timezone", required = false) String clientTz,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId, // ✅ NEW
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "deviceCapturedAtUtc", required = false) String deviceCapturedAtUtc,
            HttpServletRequest req
    ) throws Exception {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.createPhoto(uid, clientTz, deviceId, deviceCapturedAtUtc, file, requestId);
    }

    /**
     * ✅ LABEL：營養標示（multipart）
     * - provider 固定走 GEMINI（Gemini 3 Flash）
     * - 最多重試一次：由 worker 的 method-aware attempts=2 控制
     */
    @PostMapping(value = "/label", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FoodLogEnvelope label(
            @RequestHeader(value = "X-Client-Timezone", required = false) String clientTz,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId, // ✅ NEW
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "deviceCapturedAtUtc", required = false) String deviceCapturedAtUtc,
            HttpServletRequest req
    ) throws Exception {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.createLabel(uid, clientTz, deviceId, deviceCapturedAtUtc, file, requestId);
    }

    public record BarcodeRequest(String barcode) {}

    /**
     * ✅ BARCODE：MVP 先做「查不到 → TRY_LABEL」
     * - 這裡先不扣 AI quota（因為沒有打 AI）
     * - 仍會做 rateLimiter（避免被打爆）
     */
    @PostMapping(value = "/barcode", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FoodLogEnvelope barcode(
            @RequestHeader(value = "X-Client-Timezone", required = false) String clientTz,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(value = "X-App-Lang", required = false) String appLang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestBody BarcodeRequest body,
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);

        // ✅ 統一：只取第一個 lang tag（支援 Accept-Language 字串）
        String preferredLangTag = OpenFoodFactsLang.firstLangTagOrNull(
                (appLang != null && !appLang.isBlank()) ? appLang : acceptLanguage
        );

        return service.createBarcodeMvp(
                uid, clientTz, deviceId,
                body == null ? null : body.barcode(),
                preferredLangTag,
                requestId
        );
    }


    @GetMapping("/{id}")
    public FoodLogEnvelope getOne(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.getOne(uid, id, requestId);
    }

    @PostMapping("/{id}/retry")
    public FoodLogEnvelope retry(
            @PathVariable String id,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId, // ✅ NEW
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.retry(uid, id, deviceId, requestId);
    }

    @DeleteMapping("/{id}")
    public FoodLogEnvelope deleteOne(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return deleteService.deleteOne(uid, id, requestId);
    }

    @PostMapping("/{id}/save")
    public FoodLogEnvelope save(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return historyService.save(uid, id, requestId);
    }

    /**
     * ✅ 保留你原本的 SAVED 列表（不破壞既有 App）
     */
    @GetMapping
    public FoodLogListResponse listSaved(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromLocalDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toLocalDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return historyService.listSaved(uid, fromLocalDate, toLocalDate, page, size, requestId);
    }

    /**
     * ✅ S4-10：通用 history（給 UI 切換 Draft/Saved/Failed/Pending）
     * 避免跟 @GetMapping("/") 衝突，所以用 /history
     */
    @GetMapping("/history")
    public FoodLogListResponse history(
            @RequestParam String status, // DRAFT/SAVED/FAILED/PENDING
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromLocalDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toLocalDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return historyService.listByStatus(uid, status, fromLocalDate, toLocalDate, page, size, requestId);
    }

    @PostMapping(value = "/{id}/overrides", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FoodLogEnvelope override(
            @PathVariable String id,
            @RequestBody FoodLogOverrideRequest body,
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return overrideService.applyOverride(uid, id, body, requestId);
    }
}
