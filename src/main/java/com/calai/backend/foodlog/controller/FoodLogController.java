package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogOverrideRequest;
import com.calai.backend.foodlog.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.service.FoodLogHistoryService;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

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
            @RequestPart("file") MultipartFile file,
            HttpServletRequest req
    ) throws Exception {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req); // ✅ 統一
        return service.createAlbum(uid, clientTz, file, requestId);
    }

    @GetMapping("/{id}")
    public FoodLogEnvelope getOne(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req); // ✅ 統一
        return service.getOne(uid, id, requestId);
    }

    private static String requestId(HttpServletRequest req) {
        String v = req.getHeader("X-Request-Id");
        return (v == null || v.isBlank()) ? UUID.randomUUID().toString() : v;
    }

    /**
     * ✅ Dev-only：取回原始圖片（上線要關）
     * 開關：app.features.dev-image-endpoint=true
     */
    @ConditionalOnProperty(name = "app.features.dev-image-endpoint", havingValue = "true")
    @GetMapping(
            value = "/{id}/image",
            produces = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE }
    )
    public ResponseEntity<StreamingResponseBody> getImage(@PathVariable String id) {
        Long uid = auth.requireUserId();

        var opened = service.openImage(uid, id);

        StreamingResponseBody body = outputStream -> {
            try (var in = service.openImageStream(opened.objectKey())) {
                in.transferTo(outputStream);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        String ct = opened.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : opened.contentType();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ct)
                .headers(h -> {
                    if (opened.sizeBytes() > 0) {
                        h.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(opened.sizeBytes()));
                    }
                })
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @PostMapping("/{id}/retry")
    public FoodLogEnvelope retry(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);
        return service.retry(uid, id, requestId);
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
