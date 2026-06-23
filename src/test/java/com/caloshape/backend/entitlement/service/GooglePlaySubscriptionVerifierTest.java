package com.caloshape.backend.entitlement.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GooglePlaySubscriptionVerifierTest {

    @Test
    void verify_shouldKeepDevFakeSubscriptionExpiryStableAcrossRepeatedVerification() throws Exception {
        GooglePlayVerifierProperties props = new GooglePlayVerifierProperties();
        props.setDevFakeTokensEnabled(true);

        GooglePlaySubscriptionVerifier verifier = new GooglePlaySubscriptionVerifier(null, props);
        String token = "fake-dev-sub::caloshape_yearly::paid::1779497595054";

        SubscriptionVerifier.VerifiedSubscription first = verifier.verify(token);
        SubscriptionVerifier.VerifiedSubscription second = verifier.verify(token);

        assertThat(first.expiryTimeUtc()).isEqualTo(Instant.parse("2027-05-23T00:53:15.054Z"));
        assertThat(second.expiryTimeUtc()).isEqualTo(first.expiryTimeUtc());
        assertThat(second.latestOrderId()).isEqualTo("DEV-FAKE-ORDER-1779497595054");
    }
}
