package com.calai.backend.weight.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.common.storage.LocalImageStorage;
import com.calai.backend.weight.dto.*;
import com.calai.backend.weight.service.WeightService;
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
    private final LocalImageStorage images;

    public WeightController(AuthContext auth, WeightService svc, LocalImageStorage images) {
        this.auth = auth; this.svc = svc; this.images = images;
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

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
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

        // 驗證檔案型別與大小（最大 3MB）
        String photoUrl = null;
        if (photo != null && !photo.isEmpty()) {
            if (photo.getSize() > 3 * 1024 * 1024L) return ResponseEntity.badRequest().build();
            String type = photo.getContentType() == null ? "" : photo.getContentType();
            if (!(type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/heic"))) {
                return ResponseEntity.badRequest().build();
            }
            String ext = switch (type) {
                case "image/png" -> "png";
                case "image/heic" -> "heic";
                default -> "jpg";
            };
            photoUrl = images.save(uid, photo, ext);
        }

        var dto = svc.log(
                uid,
                new LogWeightRequest(weightKg, weightLbs, logDate),
                zone,
                photoUrl
        );
        return ResponseEntity.ok(dto);
    }

    @GetMapping(value="/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.List<WeightItemDto>> recent7(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        var list = svc.history7ExcludingToday(uid, svc.parseZoneOrUtc(tzHeader));
        return ResponseEntity.ok(list);
    }

    @GetMapping(value="/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SummaryDto> summary(
            @RequestParam("range") String range,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        var zone = svc.parseZoneOrUtc(tzHeader);
        var today = java.time.LocalDate.now(zone);

        java.time.LocalDate start = switch (range) {
            // 舊版 key（保留相容）
            case "week" -> today.minusDays(6);
            case "month" -> today.withDayOfMonth(1);
            case "year" -> today.minusDays(364);

            // 新 UI key
            case "season" -> today.minusDays(89);      // 90 Days
            case "half year" -> today.minusMonths(6);  // 6 Months
            case "all" -> java.time.LocalDate.of(1970, 1, 1); // 幾乎等於 all time

            default -> today.minusDays(6);
        };

        return ResponseEntity.ok(svc.summary(uid, start, today));
    }
}
