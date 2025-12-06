package com.calai.backend.userprofile.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.userprofile.common.PlanMode;
import com.calai.backend.userprofile.common.WaterMode;
import com.calai.backend.userprofile.service.UserProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/profile")
public class ProfilePlanController {

    private final UserProfileService service;
    private final AuthContext auth;

    public ProfilePlanController(UserProfileService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }
    public record WaterManualRequest(@NotNull @Min(0) Integer waterMl) {}
    public record WaterModeRequest(@NotNull WaterMode mode) {}

    public record PlanManualRequest(
            @NotNull @Min(500) Integer kcal,
            @NotNull @Min(0) Integer proteinG,
            @NotNull @Min(0) Integer carbsG,
            @NotNull @Min(0) Integer fatG
    ) {}

    public record PlanModeRequest(@NotNull PlanMode mode) {}

    @PutMapping("/plan-manual")
    public ResponseEntity<Void> setManual(@Valid @RequestBody PlanManualRequest req) {
        Long uid = auth.requireUserId();
        service.setManualPlan(uid, req.kcal(), req.proteinG(), req.carbsG(), req.fatG());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/plan-mode")
    public ResponseEntity<Void> setMode(@Valid @RequestBody PlanModeRequest req) {
        Long uid = auth.requireUserId();
        service.setPlanMode(uid, req.mode());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/water-manual")
    public ResponseEntity<Void> setWaterManual(@Valid @RequestBody WaterManualRequest req) {
        Long uid = auth.requireUserId();
        service.setManualWater(uid, req.waterMl());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/water-mode")
    public ResponseEntity<Void> setWaterMode(@Valid @RequestBody WaterModeRequest req) {
        Long uid = auth.requireUserId();
        service.setWaterMode(uid, req.mode());
        return ResponseEntity.noContent().build();
    }
}
