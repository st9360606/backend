package com.calai.backend.userprofile.controller;

import com.calai.backend.auth.security.AuthContext;

import com.calai.backend.userprofile.dto.UpdateGoalWeightRequest;
import com.calai.backend.userprofile.dto.UpsertProfileRequest;
import com.calai.backend.userprofile.dto.UserProfileDto;
import com.calai.backend.userprofile.service.UserProfileService;
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
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        String tzToPersist = validateIanaOrNull(tzHeader); // 只接受合法 IANA 時區
        UserProfileDto dto = (tzToPersist != null)
                ? svc.upsert(uid, req, tzToPersist)
                : svc.upsert(uid, req);
        return ResponseEntity.ok(dto);
    }

    /**
     * 專用更新「目標體重」的端點。
     * Body 只需要 value + unit（KG 或 LBS），伺服器會自動換算與同步兩制欄位。
     */
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

    /** 若 tz 非空且可被 ZoneId 解析，回傳標準化字串；否則回 null（不更新 DB） */
    private static String validateIanaOrNull(String tz) {
        if (tz == null || tz.isBlank()) return null;
        String trimmed = tz.trim();
        try {
            // 解析即驗證，並回傳標準 ID（避免大小寫/別名混亂）
            return ZoneId.of(trimmed).getId();
        } catch (Exception ex) {
            return null;
        }
    }
}
