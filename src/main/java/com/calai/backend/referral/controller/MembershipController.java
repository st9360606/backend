package com.calai.backend.referral.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.referral.dto.MembershipSummaryResponse;
import com.calai.backend.referral.dto.RewardHistoryItemDto;
import com.calai.backend.referral.service.MembershipSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {
    private final AuthContext authContext;
    private final MembershipSummaryService membershipSummaryService;

    @GetMapping("/me")
    public MembershipSummaryResponse me() {
        return membershipSummaryService.getMembershipSummary(authContext.requireUserId());
    }

    @GetMapping("/rewards")
    public List<RewardHistoryItemDto> rewards() {
        return membershipSummaryService.getRewardHistory(authContext.requireUserId());
    }
}
