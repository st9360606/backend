package com.calai.backend.entitlement;

import com.calai.backend.entitlement.service.SubscriptionVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EntitlementWiringGuardTest {

    @Autowired SubscriptionVerifier verifier;

    @Test
    void test_profile_should_have_subscription_verifier_available() {
        assertThat(verifier).isNotNull();
    }
}