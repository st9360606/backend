package com.calai.backend.users.auto_generate_goals.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.auto_generate_goals.dto.AutoGoalsRequest;
import com.calai.backend.users.profile.dto.UserProfileDto;
import com.calai.backend.users.profile.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/profile/auto-goals")
public class AutoGoalsController {

    private final UserProfileService svc;
    private final AuthContext auth;

    public AutoGoalsController(UserProfileService svc, AuthContext auth) {
        this.svc = svc;
        this.auth = auth;
    }

    @PostMapping("/commit")
    public ResponseEntity<UserProfileDto> commit(
            @RequestBody AutoGoalsRequest req,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tzHeader
    ) {
        Long uid = auth.requireUserId();
        return ResponseEntity.ok(svc.commitAutoGoals(uid, req, tzHeader));
    }
}
