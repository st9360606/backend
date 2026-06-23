package com.caloshape.backend.entitlement.controller;

import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.entitlement.dto.EntitlementSyncRequest;
import com.caloshape.backend.entitlement.dto.EntitlementSyncResponse;
import com.caloshape.backend.entitlement.service.EntitlementSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/entitlements")
public class EntitlementController {

    private final AuthContext auth;
    private final EntitlementSyncService service;

    @PostMapping("/sync")
    public EntitlementSyncResponse sync(@RequestBody EntitlementSyncRequest req) {
        Long userId = auth.requireUserId();
        return service.sync(userId, req);
    }

    @GetMapping("/me")
    public EntitlementSyncResponse me() {
        Long userId = auth.requireUserId();
        return service.me(userId);
    }
}
