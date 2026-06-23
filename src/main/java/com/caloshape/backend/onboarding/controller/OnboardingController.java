package com.caloshape.backend.onboarding.controller;

import com.caloshape.backend.auth.security.AuthContext;
import com.caloshape.backend.onboarding.dto.OnboardingBootstrapResponse;
import com.caloshape.backend.onboarding.service.OnboardingBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final AuthContext authContext;
    private final OnboardingBootstrapService onboardingBootstrapService;

    @GetMapping("/bootstrap")
    public OnboardingBootstrapResponse bootstrap() {
        return onboardingBootstrapService.bootstrap(authContext.requireUserId());
    }
}
