package com.calai.backend.water.controller;

import com.calai.backend.water.dto.WaterDto;
import com.calai.backend.water.service.WaterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.ZoneId;

@RestController
@RequestMapping("/water")
public class WaterController {

    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Taipei");

    private final WaterService service;

    public WaterController(WaterService service) {
        this.service = service;
    }

    /**
     * 取得今天喝水值。
     * 依照 App header "X-Client-Timezone" 判斷使用者「今天」是哪一天。
     * 若 header 缺失或非法，fallback 為 Asia/Taipei。
     */
    @GetMapping("/today")
    public ResponseEntity<WaterDto.WaterSummaryDto> today(
            Principal principal,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long userId = Long.parseLong(principal.getName());
        ZoneId zone = resolveZoneIdOrDefault(tzHeader);
        return ResponseEntity.ok(service.getToday(userId, zone));
    }

    /**
     * cupsDelta = +1 表示多喝一杯
     * cupsDelta = -1 表示扣一杯（不會低於 0）
     */
    @PostMapping("/increment")
    public ResponseEntity<WaterDto.WaterSummaryDto> increment(
            Principal principal,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader,
            @RequestBody WaterDto.AdjustRequest body
    ) {
        Long userId = Long.parseLong(principal.getName());
        ZoneId zone = resolveZoneIdOrDefault(tzHeader);
        return ResponseEntity.ok(service.adjustToday(userId, zone, body.cupsDelta()));
    }

    @GetMapping("/weekly")
    public ResponseEntity<WaterDto.WaterWeeklyChartDto> weekly(
            Principal principal,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long userId = Long.parseLong(principal.getName());
        ZoneId zone = resolveZoneIdOrDefault(tzHeader);
        return ResponseEntity.ok(service.getRecent7Days(userId, zone));
    }

    private ZoneId resolveZoneIdOrDefault(String tzHeader) {
        if (tzHeader == null || tzHeader.isBlank()) {
            return DEFAULT_ZONE_ID;
        }
        try {
            return ZoneId.of(tzHeader.trim());
        } catch (Exception ex) {
            return DEFAULT_ZONE_ID;
        }
    }
}
