package com.calai.backend.entitlement.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.trial.TrialGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trial")
public class TrialController {

    private final AuthContext auth;
    private final TrialGrantService trialGrantService;
    private final UserEntitlementRepository entitlementRepo;

    @Value("${app.trial.days:7}")
    private int trialDays;

    @PostMapping("/grant")
    @Transactional
    public Map<String, Object> grantTrial(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        Long uid = auth.requireUserId();
        Instant now = Instant.now();

        // 1) 檢查 email/device 一次性
        trialGrantService.ensureTrialEligibleOrThrow(uid, deviceId, now);

        // 2) 發 TRIAL entitlement
        entitlementRepo.expireActiveByUserId(uid, now);

        UserEntitlementEntity e = new UserEntitlementEntity();
        e.setUserId(uid);
        e.setEntitlementType("TRIAL");
        e.setStatus("ACTIVE");
        e.setValidFromUtc(now);
        e.setValidToUtc(now.plus(trialDays, ChronoUnit.DAYS));
        e.setLastVerifiedAtUtc(now);

        entitlementRepo.save(e);

        return Map.of(
                "ok", true,
                "tier", "TRIAL",
                "validToUtc", e.getValidToUtc().toString()
        );
    }
}
