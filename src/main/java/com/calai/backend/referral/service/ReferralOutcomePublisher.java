package com.calai.backend.referral.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ReferralOutcomePublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publish(ReferralOutcomeEvent event) {
        eventPublisher.publishEvent(event);
    }
}
