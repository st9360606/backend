package com.calai.backend.entitlement.web.contract;

import com.calai.backend.auth.entity.AuthProvider;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.BillingProductProperties;
import com.calai.backend.entitlement.service.SubscriptionVerifier;
import com.calai.backend.testsupport.db.MySqlContainerBaseTest;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class EntitlementSyncContractTest extends MySqlContainerBaseTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired UserRepo userRepo;
    @Autowired UserEntitlementRepository entitlementRepo;

    @TestConfiguration
    static class TestOverrideConfig {

        static final AtomicLong UID = new AtomicLong(0);

        @Bean
        @Primary
        public AuthContext authContext() {
            return new AuthContext() {
                @Override
                public Long requireUserId() {
                    long id = UID.get();
                    if (id <= 0) {
                        throw new IllegalStateException("TEST_USER_ID_NOT_SET");
                    }
                    return id;
                }
            };
        }

        @Bean
        @Primary
        public SubscriptionVerifier verifier() {
            return purchaseToken -> {
                if ("trialTok123".equals(purchaseToken)) {
                    return new SubscriptionVerifier.VerifiedSubscription(
                            true,                                           // active
                            "monthly001",                                   // productId
                            Instant.now().plus(3, ChronoUnit.DAYS),          // expiryTimeUtc
                            true,                                           // freeTrial
                            "SUBSCRIPTION_STATE_ACTIVE",                    // subscriptionState
                            "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",           // acknowledgementState
                            true,                                           // autoRenewEnabled
                            "FREE_TRIAL",                                   // offerPhase
                            "GPA.1111-2222-3333-44444",                    // latestOrderId
                            null,                                           // linkedPurchaseToken
                            true,                                           // testPurchase
                            false                                           // pending
                    );
                }

                return new SubscriptionVerifier.VerifiedSubscription(
                        true,                                               // active
                        "monthly001",                                       // productId
                        Instant.now().plus(30, ChronoUnit.DAYS),             // expiryTimeUtc
                        false,                                              // freeTrial
                        "SUBSCRIPTION_STATE_ACTIVE",                        // subscriptionState
                        "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",               // acknowledgementState
                        true,                                               // autoRenewEnabled
                        "BASE",                                             // offerPhase
                        "GPA.5555-6666-7777-88888",                        // latestOrderId
                        null,                                               // linkedPurchaseToken
                        true,                                               // testPurchase
                        false                                               // pending
                );
            };
        }

        @Bean
        @Primary
        public BillingProductProperties billingProductProperties() {
            var p = new BillingProductProperties();
            p.setMonthly(new HashSet<>(List.of("monthly001")));
            p.setYearly(new HashSet<>());
            return p;
        }
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void sync_should_insert_active_paid_entitlement() throws Exception {
        User user = createUser();
        TestOverrideConfig.UID.set(user.getId());

        var body = new EntitlementSyncRequest(
                List.of(new EntitlementSyncRequest.PurchaseTokenPayload("monthly001", "tok123"))
        );

        mvc.perform(post("/api/v1/entitlements/sync")
                        .contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.entitlementType").value("MONTHLY"))
                .andExpect(jsonPath("$.premiumStatus").value("PREMIUM"))
                .andExpect(jsonPath("$.trialEligible").value(true))
                .andExpect(jsonPath("$.trialEndsAt").doesNotExist())
                .andExpect(jsonPath("$.trialDaysLeft").doesNotExist());

        var active = entitlementRepo.findActive(
                user.getId(),
                Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, 5)
        );

        assertThat(active).isNotEmpty();

        var e = active.get(0);
        assertThat(e.getEntitlementType()).isEqualTo("MONTHLY");
        assertThat(e.getStatus()).isEqualTo("ACTIVE");

        // 這些是這次新增的 Google Play subscription metadata
        assertThat(e.getSource()).isEqualTo("GOOGLE_PLAY");
        assertThat(e.getProductId()).isEqualTo("monthly001");
        assertThat(e.getSubscriptionState()).isEqualTo("SUBSCRIPTION_STATE_ACTIVE");
        assertThat(e.getOfferPhase()).isEqualTo("BASE");
        assertThat(e.getAutoRenewEnabled()).isTrue();
        assertThat(e.getAcknowledgementState()).isEqualTo("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
        assertThat(e.getLatestOrderId()).isEqualTo("GPA.5555-6666-7777-88888");
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void sync_should_map_google_play_free_trial_to_trial_status() throws Exception {
        User user = createUser();
        TestOverrideConfig.UID.set(user.getId());

        var body = new EntitlementSyncRequest(
                List.of(new EntitlementSyncRequest.PurchaseTokenPayload("monthly001", "trialTok123"))
        );

        mvc.perform(post("/api/v1/entitlements/sync")
                        .contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.entitlementType").value("TRIAL"))
                .andExpect(jsonPath("$.premiumStatus").value("TRIAL"))
                .andExpect(jsonPath("$.trialEligible").value(false))
                .andExpect(jsonPath("$.trialEndsAt").exists())
                .andExpect(jsonPath("$.trialDaysLeft").value(3));

        var active = entitlementRepo.findActive(
                user.getId(),
                Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, 5)
        );

        assertThat(active).isNotEmpty();

        var e = active.get(0);
        assertThat(e.getEntitlementType()).isEqualTo("TRIAL");
        assertThat(e.getStatus()).isEqualTo("ACTIVE");

        // 重點：Google Play trial 也要保留 Google Play metadata
        assertThat(e.getSource()).isEqualTo("GOOGLE_PLAY");
        assertThat(e.getProductId()).isEqualTo("monthly001");
        assertThat(e.getSubscriptionState()).isEqualTo("SUBSCRIPTION_STATE_ACTIVE");
        assertThat(e.getOfferPhase()).isEqualTo("FREE_TRIAL");
        assertThat(e.getAutoRenewEnabled()).isTrue();
        assertThat(e.getAcknowledgementState()).isEqualTo("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
        assertThat(e.getLatestOrderId()).isEqualTo("GPA.1111-2222-3333-44444");
    }

    private User createUser() {
        User u = new User();
        u.setEmail("entitlement-test-" + System.nanoTime() + "@example.com");
        u.setProvider(AuthProvider.EMAIL);
        u.setStatus("ACTIVE");
        return userRepo.saveAndFlush(u);
    }
}
