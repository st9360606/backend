package com.calai.backend.referral.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimReferralRequest(
        @NotBlank
        @Size(min = 6, max = 24)
        String promoCode
) {}
