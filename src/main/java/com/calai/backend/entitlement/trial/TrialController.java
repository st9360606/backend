package com.calai.backend.entitlement.trial;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trial")
public class TrialController {

    private final AuthContext auth;
    private final TrialGrantService trialGrantService;
    private final UserEntitlementRepository entitlementRepo;

    @Value("${app.trial.days:3}")
    private int trialDays;

    @PostMapping("/grant")
    @Transactional
    public TrialGrantResponse grantTrial(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        Long uid = auth.requireUserId();
        Instant now = Instant.now();

        UserEntitlementEntity active = entitlementRepo
                .findActiveBestFirst(uid, now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);

        if (active != null) {
            String type = active.getEntitlementType();

            if ("MONTHLY".equalsIgnoreCase(type) || "YEARLY".equalsIgnoreCase(type)) {
                return new TrialGrantResponse(
                        true,
                        "PREMIUM",
                        type,
                        active.getValidToUtc()
                );
            }

            if ("TRIAL".equalsIgnoreCase(type)) {
                return new TrialGrantResponse(
                        true,
                        "TRIAL",
                        "TRIAL",
                        active.getValidToUtc()
                );
            }
        }

        boolean trialAlreadyUsed = entitlementRepo.existsByUserIdAndEntitlementType(uid, "TRIAL");
        if (trialAlreadyUsed) {
            throw new TrialNotEligibleException("TRIAL_ALREADY_USED");
        }

        trialGrantService.ensureTrialEligibleOrThrow(uid, deviceId, now);

        UserEntitlementEntity e = new UserEntitlementEntity();
        e.setUserId(uid);
        e.setEntitlementType("TRIAL");
        e.setStatus("ACTIVE");
        e.setValidFromUtc(now);
        e.setValidToUtc(now.plus(trialDays, ChronoUnit.DAYS));
        e.setLastVerifiedAtUtc(now);

        entitlementRepo.save(e);

        return new TrialGrantResponse(
                true,
                "TRIAL",
                "TRIAL",
                e.getValidToUtc()
        );
    }
}
