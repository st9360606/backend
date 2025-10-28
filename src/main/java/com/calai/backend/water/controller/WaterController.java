package com.calai.backend.water.controller;

import com.calai.backend.water.dto.WaterDtos;
import com.calai.backend.water.service.WaterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.ZoneId;

@RestController
@RequestMapping("/water")
public class WaterController {

    private final WaterService service;

    public WaterController(WaterService service) {
        this.service = service;
    }

    /**
     * 取得今天喝水值。
     * 依照 App header "X-Client-Timezone" 判斷使用者「今天」是哪一天。
     */
    @GetMapping("/today")
    public ResponseEntity<WaterDtos.WaterSummaryDto> today(
            Principal principal,
            @RequestHeader("X-Client-Timezone") String tzHeader
    ) {
        Long userId = Long.parseLong(principal.getName()); // 假設 principal.name 是 userId
        ZoneId zone = ZoneId.of(tzHeader);
        return ResponseEntity.ok(service.getToday(userId, zone));
    }

    /**
     * cupsDelta = +1 表示多喝一杯
     * cupsDelta = -1 表示扣一杯（不會低於 0）
     */
    @PostMapping("/increment")
    public ResponseEntity<WaterDtos.WaterSummaryDto> increment(
            Principal principal,
            @RequestHeader("X-Client-Timezone") String tzHeader,
            @RequestBody WaterDtos.AdjustRequest body
    ) {
        Long userId = Long.parseLong(principal.getName());
        ZoneId zone = ZoneId.of(tzHeader);
        return ResponseEntity.ok(service.adjustToday(userId, zone, body.cupsDelta()));
    }
}
