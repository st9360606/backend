package com.caloshape.backend.auth.controller;

import com.caloshape.backend.auth.dto.AuthResponse;
import com.caloshape.backend.auth.dto.StartRequest;
import com.caloshape.backend.auth.dto.StartResponse;
import com.caloshape.backend.auth.dto.VerifyRequest;
import com.caloshape.backend.auth.service.EmailAuthService; // ✅ 確認這個 import
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/auth/email")
public class EmailAuthController {

    private final EmailAuthService service;

    public EmailAuthController(EmailAuthService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<StartResponse> start(
            @Valid @RequestBody StartRequest req,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest http
    ) {
        String ip = clientIp(http);
        String ua = Optional.ofNullable(http.getHeader("User-Agent")).orElse("");
        return ResponseEntity.ok(service.start(req, deviceId, ip, ua));
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verify(
            @Valid @RequestBody VerifyRequest req,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest http
    ) {
        String ip = clientIp(http);
        String ua = Optional.ofNullable(http.getHeader("User-Agent")).orElse("");
        // ✅ 呼叫四參數版（若你暫時不想帶這些，也可呼叫 service.verify(req) 的相容版）
        return ResponseEntity.ok(service.verify(req, deviceId, ip, ua));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
