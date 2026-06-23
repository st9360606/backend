package com.caloshape.backend.referral.controller;

import com.caloshape.backend.internal.InternalApiGuard;
import com.caloshape.backend.referral.service.ReferralPendingProcessorJob;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/referrals")
public class InternalReferralController {

    private final InternalApiGuard internalApiGuard;
    private final ReferralPendingProcessorJob referralPendingProcessorJob;

    @PostMapping("/process-pending")
    public Map<String, Object> processPending(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        internalApiGuard.requireValidToken(internalToken);

        referralPendingProcessorJob.processPendingVerification();

        return Map.of(
                "ok", true,
                "message", "Referral pending processor executed"
        );
    }
}
