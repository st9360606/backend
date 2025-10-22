package com.calai.backend.fasting.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.fasting.dto.*;
import com.calai.backend.fasting.service.FastingPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/fasting-plan")
@RequiredArgsConstructor
public class FastingPlanController {
    private final FastingPlanService service;
    private final AuthContext auth;

    @GetMapping("/me")
    public ResponseEntity<FastingPlanDto> getMine() {
        var userId = auth.requireUserId();
        return service.find(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build()); // ★ 真正回 404
    }

    @PutMapping("/me")
    public FastingPlanDto upsert(@RequestBody UpsertFastingPlanReq req) {
        var userId = auth.requireUserId();
        return service.upsert(userId, req);
    }

    @GetMapping("/next-triggers")
    public NextTriggersResp next(
            @RequestParam String planCode,
            @RequestParam String startTime,
            @RequestParam String timeZone
    ) {
        return service.nextTriggers(planCode, startTime, timeZone, Instant.now());
    }
}
