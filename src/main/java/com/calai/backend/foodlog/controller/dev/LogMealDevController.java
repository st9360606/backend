package com.calai.backend.foodlog.controller.dev;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.service.LogMealTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile({"dev","local"})
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/dev/logmeal")
public class LogMealDevController {

    private final AuthContext auth;
    private final LogMealTokenService tokenService;

    public record UpsertTokenRequest(String apiUserToken) {}

    @PostMapping("/token")
    public ResponseEntity<?> upsert(@RequestBody UpsertTokenRequest req) {
        Long uid = auth.requireUserId();
        tokenService.upsertToken(uid, req.apiUserToken());
        return ResponseEntity.ok().build();
    }
}
