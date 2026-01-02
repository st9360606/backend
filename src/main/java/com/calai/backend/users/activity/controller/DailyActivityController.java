package com.calai.backend.users.activity.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.activity.entity.UserDailyActivity;
import com.calai.backend.users.activity.service.DailyActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me")
public class DailyActivityController {

    private final DailyActivityService service;
    private final AuthContext auth;

    public DailyActivityController(DailyActivityService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    public record UpsertDailyActivityRequest(
            @NotNull LocalDate localDate,
            @NotBlank String timezone,
            Long steps,                       // nullable
            Double activeKcal,                // nullable
            UserDailyActivity.IngestSource ingestSource, // nullable -> default HEALTH_CONNECT
            String dataOriginPackage,         // HEALTH_CONNECT 建議必填（service 會檢查）
            String dataOriginName
    ) {}

    public record DailyActivityItemDto(
            LocalDate localDate,
            String timezone,
            Long steps,
            Double activeKcal,
            UserDailyActivity.IngestSource ingestSource,
            String dataOriginPackage,
            String dataOriginName
    ) {}

    @PutMapping("/daily-activity")
    public ResponseEntity<Void> upsert(@Valid @RequestBody UpsertDailyActivityRequest req) {
        Long uid = auth.requireUserId();

        service.upsert(uid, new DailyActivityService.UpsertReq(
                req.localDate(),
                req.timezone(),
                req.steps(),
                req.activeKcal(),
                req.ingestSource(),
                req.dataOriginPackage(),
                req.dataOriginName()
        ));

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/daily-activity")
    public ResponseEntity<List<DailyActivityItemDto>> getRange(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to
    ) {
        Long uid = auth.requireUserId();
        var list = service.getRange(uid, from, to).stream()
                .map(a -> new DailyActivityItemDto(
                        a.getLocalDate(),
                        a.getTimezone(),
                        a.getSteps(),
                        a.getActiveKcal(),
                        a.getIngestSource(),
                        a.getDataOriginPackage(),
                        a.getDataOriginName()
                ))
                .toList();

        return ResponseEntity.ok(list);
    }
}
