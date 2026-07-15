package com.caloshape.backend.weight.controller;

import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.weight.dto.*;
import com.caloshape.backend.weight.service.WeightService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/weights")
public class WeightController {
    private final AuthContext auth;
    private final WeightService svc;

    public WeightController(AuthContext auth, WeightService svc) {
        this.auth = auth;
        this.svc = svc;
    }

    @PostMapping(value = "/baseline", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> ensureBaseline(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        var zone = svc.parseZoneOrUtc(tzHeader);
        svc.ensureBaselineFromProfile(uid, zone);
        // 沒有內容，只要成功 204 或 200 都可以；這裡用 204 比較語意化
        return ResponseEntity.noContent().build();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WeightItemDto> logWeight(
            @RequestParam(value = "weightKg",  required = false) BigDecimal weightKg,
            @RequestParam(value = "weightLbs", required = false) BigDecimal weightLbs,
            @RequestParam(value = "logDate",   required = false) String logDateStr,
            @RequestPart(value = "photo",      required = false) MultipartFile photo,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) throws Exception {
        Long uid = auth.requireUserId();
        ZoneId zone = svc.parseZoneOrUtc(tzHeader);

        LocalDate logDate = (logDateStr != null && !logDateStr.isBlank())
                ? LocalDate.parse(logDateStr)
                : null;

        var dto = svc.logWithPhoto(uid, new LogWeightRequest(weightKg, weightLbs, logDate), zone, photo);
        return ResponseEntity.ok(dto);
    }

    @GetMapping(value="/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.List<WeightItemDto>> recent7(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        // ✅ 這裡不再需要 today / zone 來過濾，只要最新 7 筆
        var list = svc.history7Latest(uid);
        return ResponseEntity.ok(list);
    }

    @DeleteMapping(value = "/{logDate}")
    public ResponseEntity<Void> deleteWeight(
            @PathVariable("logDate") String logDateStr
    ) {
        Long uid = auth.requireUserId();
        LocalDate logDate = LocalDate.parse(logDateStr);
        svc.delete(uid, logDate);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value="/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SummaryDto> summary(
            @RequestParam("range") String range,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        var zone = svc.parseZoneOrUtc(tzHeader);
        var today = LocalDate.now(zone);
        return ResponseEntity.ok(svc.summaryForRange(uid, range, today));
    }

    @GetMapping(value = "/photos/{filename:.+}")
    public ResponseEntity<Resource> photo(@PathVariable String filename) {
        Long uid = auth.requireUserId();
        return svc.findOwnedPhoto(uid, filename)
                .map(photo -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(photo.mediaType())
                        .contentLength(photo.contentLength())
                        .body(photo.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
