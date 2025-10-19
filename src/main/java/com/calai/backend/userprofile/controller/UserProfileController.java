package com.calai.backend.userprofile.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.userprofile.dto.UpsertProfileRequest;
import com.calai.backend.userprofile.dto.UserProfileDto;
import com.calai.backend.userprofile.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/profile")
public class UserProfileController {
    private final UserProfileService svc;
    private final AuthContext auth;

    public UserProfileController(UserProfileService svc, AuthContext auth) {
        this.svc = svc;
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<UserProfileDto> getMyProfile() {
        Long uid = auth.requireUserId();
        // 不存在回 404（避免 401 觸發 OkHttp Authenticator 的 refresh 循環）
        if (!svc.exists(uid)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(svc.getOrThrow(uid));
    }

    @PutMapping
    public UserProfileDto upsertMyProfile(@RequestBody UpsertProfileRequest req) {
        Long uid = auth.requireUserId();
        return svc.upsert(uid, req);
    }
}

