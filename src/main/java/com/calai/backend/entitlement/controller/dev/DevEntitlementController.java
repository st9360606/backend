package com.calai.backend.entitlement.controller.dev;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Profile({"dev","local"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dev/entitlements")
public class DevEntitlementController {

    private final AuthContext auth;
    private final UserEntitlementRepository repo;

    @PostMapping("/grant")
    @Transactional
    public Map<String, Object> grant(@RequestParam(defaultValue = "MONTHLY") String tier) {
        Long uid = auth.requireUserId();
        Instant now = Instant.now();

        repo.expireActiveByUserId(uid, now);

        UserEntitlementEntity e = new UserEntitlementEntity();
        e.setUserId(uid);
        e.setEntitlementType(tier.trim().toUpperCase());
        e.setStatus("ACTIVE");
        e.setValidFromUtc(now);
        e.setValidToUtc(now.plus(30, ChronoUnit.DAYS));
        e.setLastVerifiedAtUtc(now);

        repo.save(e);

        return Map.of(
                "ok", true,
                "tier", e.getEntitlementType(),
                "validToUtc", e.getValidToUtc().toString()
        );
    }
}
