package com.calai.backend.entitlement;

import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.calai.backend.entitlement.service.SubscriptionVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class EntitlementSyncContractTest extends MySqlContainerBaseTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Autowired UserRepo userRepo;
    @Autowired
    UserEntitlementRepository entitlementRepo;

    @TestConfiguration
    static class TestOverrideConfig {

        static final AtomicLong UID = new AtomicLong(0);

        @Bean
        @Primary
        public com.calai.backend.auth.security.AuthContext authContext() {
            return new com.calai.backend.auth.security.AuthContext() {
                @Override public Long requireUserId() { return UID.get(); }
            };
        }

        @Bean
        @Primary
        public SubscriptionVerifier verifier() {
            return purchaseToken -> new SubscriptionVerifier.VerifiedSubscription(
                    true,
                    "monthly001",
                    Instant.now().plus(30, ChronoUnit.DAYS)
            );
        }

        @Bean
        @Primary
        public com.calai.backend.entitlement.service.BillingProductProperties billingProductProperties() {
            var p = new com.calai.backend.entitlement.service.BillingProductProperties();
            p.setMonthly(new java.util.HashSet<>(List.of("monthly001")));
            p.setYearly(new java.util.HashSet<>());
            return p;
        }
    }

    @Test
    @WithMockUser(username = "test", roles = {"USER"})
    void sync_should_insert_active_entitlement() throws Exception {
        // ensure user id=1 exists
        if (userRepo.findById(1L).isEmpty()) {
            User u = new User();
            u.setEmail("test@example.com");
            u.setProvider(com.calai.backend.auth.entity.AuthProvider.EMAIL);
            u.setStatus("ACTIVE");
            userRepo.saveAndFlush(u);
            EntitlementSyncContractTest.TestOverrideConfig.UID.set(u.getId());
            // 你 DB 可能自增不是 1；若不是 1，請改 AuthContext 回傳 u.getId()
            // 這裡先假設你的測試庫會從 1 開始
        }

        var body = new EntitlementSyncRequest(
                List.of(new EntitlementSyncRequest.PurchaseTokenPayload("monthly001", "tok123"))
        );

        mvc.perform(post("/api/v1/entitlements/sync")
                        .contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.entitlementType").value("MONTHLY"));

        var active = entitlementRepo.findActive(1L, Instant.now(), org.springframework.data.domain.PageRequest.of(0, 5));
        assertThat(active).isNotEmpty();
        assertThat(active.get(0).getEntitlementType()).isEqualTo("MONTHLY");
        assertThat(active.get(0).getStatus()).isEqualTo("ACTIVE");
    }
}
