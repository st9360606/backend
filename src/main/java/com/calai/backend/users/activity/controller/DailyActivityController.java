package com.calai.backend.users.activity.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.activity.service.DailyActivityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;

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
            @NotBlank String localDate,
            @NotBlank String timezone,
            Long steps,
            Double totalKcal,
            String source
    ) {}

    @PutMapping("/daily-activity")
    public ResponseEntity<Void> upsert(@Valid @RequestBody UpsertDailyActivityRequest req) {
        Long uid = auth.requireUserId();
        service.upsert(
                uid,
                new DailyActivityService.UpsertReq(
                        LocalDate.parse(req.localDate()),
                        req.timezone(),
                        req.steps(),
                        req.totalKcal(),
                        req.source()
                )
        );
        return ResponseEntity.noContent().build();
    }
}
