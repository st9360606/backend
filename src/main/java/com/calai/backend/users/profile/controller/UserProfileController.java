package com.calai.backend.users.profile.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.profile.dto.UpdateGoalWeightRequest;
import com.calai.backend.users.profile.dto.UpsertProfileRequest;
import com.calai.backend.users.profile.dto.UserProfileDto;
import com.calai.backend.users.profile.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/users/me/profile")
public class UserProfileController {
    private final UserProfileService svc;
    private final AuthContext auth;

    public UserProfileController(UserProfileService svc, AuthContext auth) {
        this.svc = svc;
        this.auth = auth;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserProfileDto> getMyProfile() {
        Long uid = auth.requireUserId();
        if (!svc.exists(uid)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(svc.getOrThrow(uid));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserProfileDto> upsertMyProfile(
            @Valid @RequestBody UpsertProfileRequest req,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader,
            @RequestHeader(value = "X-Profile-Source", required = false) String sourceHeader
    ) {
        Long uid = auth.requireUserId();

        String tzToPersist = validateIanaOrNull(tzHeader); // 只接受合法 IANA 時區
        boolean allowPlanRecalc = isOnboardingSource(sourceHeader);

        UserProfileDto dto = (tzToPersist != null)
                ? svc.upsert(uid, req, tzToPersist, allowPlanRecalc)
                : svc.upsert(uid, req, null, allowPlanRecalc);

        return ResponseEntity.ok(dto);
    }

    @PutMapping(
            path = "/goal-weight",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<UserProfileDto> updateGoalWeight(
            @Valid @RequestBody UpdateGoalWeightRequest req
    ) {
        Long uid = auth.requireUserId();
        UserProfileDto dto = svc.updateGoalWeight(uid, req);
        return ResponseEntity.ok(dto);
    }

    private static boolean isOnboardingSource(String sourceHeader) {
        if (sourceHeader == null) return false;
        return "ONBOARDING".equalsIgnoreCase(sourceHeader.trim());
    }

    /** 若 tz 非空且可被 ZoneId 解析，回傳標準化字串；否則回 null（不更新 DB） */
    private static String validateIanaOrNull(String tz) {
        if (tz == null || tz.isBlank()) return null;
        String trimmed = tz.trim();
        try {
            return ZoneId.of(trimmed).getId();
        } catch (Exception ex) {
            return null;
        }
    }
}
