package com.calai.backend.auth.controller;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.calai.backend.auth.dto.RefreshRequest;
import com.calai.backend.auth.service.GoogleAuthService;
import com.calai.backend.auth.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*") // 開發期先放行
@RequiredArgsConstructor
public class AuthController {

    private final GoogleAuthService googleAuthService;

    private final TokenService tokenService;

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleSignInExchangeRequest body,
                                               HttpServletRequest req) throws Exception {
        // 1) 驗證 idToken、2) upsert user、3) 簽發並寫 DB
        var result = googleAuthService.exchange(body,
                req.getHeader("x-device-id"),
                req.getRemoteAddr(),
                req.getHeader("User-Agent"));

        // result 直接是 AuthResponse(accessToken, refreshToken)
        return ResponseEntity.ok(result);
    }


    @GetMapping("/health")
    public String health() { return "OK"; }

    // auth/AuthController.java （在你現有的上面補這兩支）
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest req,
                                     HttpServletRequest servletReq) {
        try {
            var pair = tokenService.rotateRefresh(
                    req.refreshToken(), req.deviceId(),
                    servletReq.getRemoteAddr(),
                    servletReq.getHeader("User-Agent")
            );
            return ResponseEntity.ok(new AuthResponse(pair.accessToken(), pair.refreshToken()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", "INVALID_REFRESH_TOKEN",
                    "message", "Refresh token invalid or expired"
            ));
        }
    }


    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value="Authorization", required=false) String auth,
                                       @RequestBody(required=false) RefreshRequest req) {
        // 可選：把當前 Access 與指定 Refresh 全撤銷
        if (auth != null && auth.startsWith("Bearer ")) {
            tokenService.revokeToken(auth.substring(7));
        }
        if (req != null && req.refreshToken() != null) {
            tokenService.revokeToken(req.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

}
