package com.calai.backend.auth.controller;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.GoogleSignInExchangeRequest;
import com.calai.backend.auth.service.GoogleAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*") // 開發期先放行
public class AuthController {

    private final GoogleAuthService service;

    public AuthController(GoogleAuthService service) {
        this.service = service;
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleSignInExchangeRequest body) throws Exception {
        AuthResponse resp = service.exchange(body);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/health")
    public String health() { return "OK"; }
}
