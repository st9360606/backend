package com.calai.backend.referral.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.referral.dto.ClaimReferralRequest;
import com.calai.backend.referral.dto.ReferralSummaryResponse;
import com.calai.backend.referral.service.ReferralClaimService;
import com.calai.backend.referral.service.ReferralQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {
    private final AuthContext authContext;
    private final ReferralQueryService referralQueryService;
    private final ReferralClaimService referralClaimService;

    @GetMapping("/me")
    public ReferralSummaryResponse me() {
        Long userId = authContext.requireUserId();
        return referralQueryService.getSummary(userId);
    }

    @PostMapping("/claim")
    public ResponseEntity<Void> claim(@Valid @RequestBody ClaimReferralRequest request) {
        Long userId = authContext.requireUserId();
        referralClaimService.claim(userId, request.promoCode());
        return ResponseEntity.noContent().build();
    }
}
