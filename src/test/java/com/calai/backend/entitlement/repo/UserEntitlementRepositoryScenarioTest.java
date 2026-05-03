package com.calai.backend.entitlement.repo;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class UserEntitlementRepositoryScenarioTest {

    @Autowired
    private UserEntitlementRepository repository;

    private final Instant now = Instant.parse("2026-05-02T00:00:00Z");

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void findActiveBestFirst_shouldExcludePendingDirtyRowEvenWhenPaymentStateIsNull() {
        repository.save(entitlement(
                "pending-dirty",
                101L,
                "MONTHLY",
                "ACTIVE",
                "GOOGLE_PLAY",
                "SUBSCRIPTION_STATE_PENDING",
                null,
                now.plusSeconds(7 * 24 * 3600L),
                "cipher-pending",
                "ACKNOWLEDGEMENT_STATE_PENDING"
        ));

        List<UserEntitlementEntity> active = repository.findActiveBestFirst(
                101L,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(active).isEmpty();
    }

    @Test
    void findActiveBestFirst_shouldExcludeOnHoldRevokedExpiredPausedDirtyRowsEvenWhenPaymentStateIsNull() {
        repository.save(entitlement("hold", 102L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_ON_HOLD", null, now.plusSeconds(86400), "cipher-hold", "ACKNOWLEDGEMENT_STATE_PENDING"));
        repository.save(entitlement("revoked", 102L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_REVOKED", null, now.plusSeconds(86400), "cipher-revoked", "ACKNOWLEDGEMENT_STATE_PENDING"));
        repository.save(entitlement("expired-state", 102L, "YEARLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_EXPIRED", null, now.plusSeconds(86400), "cipher-expired", "ACKNOWLEDGEMENT_STATE_PENDING"));
        repository.save(entitlement("paused", 102L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_PAUSED", null, now.plusSeconds(86400), "cipher-paused", "ACKNOWLEDGEMENT_STATE_PENDING"));

        List<UserEntitlementEntity> active = repository.findActiveBestFirst(
                102L,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(active).isEmpty();
    }

    @Test
    void findActiveBestFirst_shouldAllowCanceledButNotExpiredPaidSubscription() {
        repository.save(entitlement(
                "cancel-not-expired",
                103L,
                "MONTHLY",
                "ACTIVE",
                "GOOGLE_PLAY",
                "SUBSCRIPTION_STATE_CANCELED",
                "OK",
                now.plusSeconds(10 * 24 * 3600L),
                "cipher-canceled",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        ));

        List<UserEntitlementEntity> active = repository.findActiveBestFirst(
                103L,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getEntitlementType()).isEqualTo("MONTHLY");
    }

    @Test
    void findActiveBestFirst_shouldAllowGracePeriodAsPaymentIssueEntitlement() {
        repository.save(entitlement(
                "grace",
                104L,
                "YEARLY",
                "ACTIVE",
                "GOOGLE_PLAY",
                "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
                "GRACE",
                now.plusSeconds(3 * 24 * 3600L),
                "cipher-grace",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        ));

        List<UserEntitlementEntity> active = repository.findActiveBestFirst(
                104L,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getSubscriptionState()).isEqualTo("SUBSCRIPTION_STATE_IN_GRACE_PERIOD");
        assertThat(active.getFirst().getPaymentState()).isEqualTo("GRACE");
    }

    @Test
    void findAnyActivePaidGooglePlayForReferral_shouldExcludePendingDirtyRow() {
        repository.save(entitlement(
                "pending-referral",
                105L,
                "MONTHLY",
                "ACTIVE",
                "GOOGLE_PLAY",
                "SUBSCRIPTION_STATE_PENDING",
                null,
                now.plusSeconds(86400),
                "cipher-pending-referral",
                "ACKNOWLEDGEMENT_STATE_PENDING"
        ));

        List<UserEntitlementEntity> deferable = repository.findAnyActivePaidGooglePlayForReferral(
                105L,
                now,
                PageRequest.of(0, 10)
        );

        assertThat(deferable).isEmpty();
    }

    @Test
    void findAckPendingGooglePlayEntitlements_shouldReturnOnlyActiveUnblockedUnacknowledgedRows() {
        Instant old = now.minusSeconds(3600);
        UserEntitlementEntity ackNeeded = entitlement("ack-needed", 106L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_ACTIVE", "OK", now.plusSeconds(86400), "cipher-ack", "ACKNOWLEDGEMENT_STATE_PENDING");
        ackNeeded.setUpdatedAtUtc(old);
        repository.save(ackNeeded);

        UserEntitlementEntity pendingBlocked = entitlement("ack-pending-blocked", 106L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_PENDING", null, now.plusSeconds(86400), "cipher-pending-blocked", "ACKNOWLEDGEMENT_STATE_PENDING");
        pendingBlocked.setUpdatedAtUtc(old);
        repository.save(pendingBlocked);

        UserEntitlementEntity alreadyAcked = entitlement("already-acked", 106L, "MONTHLY", "ACTIVE", "GOOGLE_PLAY", "SUBSCRIPTION_STATE_ACTIVE", "OK", now.plusSeconds(86400), "cipher-already-acked", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
        alreadyAcked.setUpdatedAtUtc(old);
        repository.save(alreadyAcked);

        List<UserEntitlementEntity> rows = repository.findAckPendingGooglePlayEntitlements(
                now.minusSeconds(600),
                PageRequest.of(0, 10)
        );

        assertThat(rows)
                .extracting(UserEntitlementEntity::getId)
                .containsExactly("ack-needed");
    }

    private UserEntitlementEntity entitlement(
            String id,
            Long userId,
            String entitlementType,
            String status,
            String source,
            String subscriptionState,
            String paymentState,
            Instant validToUtc,
            String purchaseTokenCiphertext,
            String acknowledgementState
    ) {
        UserEntitlementEntity entity = new UserEntitlementEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setEntitlementType(entitlementType);
        entity.setStatus(status);
        entity.setSource(source);
        entity.setValidFromUtc(now.minusSeconds(86400));
        entity.setValidToUtc(validToUtc);
        entity.setSubscriptionState(subscriptionState);
        entity.setPaymentState(paymentState);
        entity.setPurchaseTokenHash("hash-" + id);
        entity.setPurchaseTokenCiphertext(purchaseTokenCiphertext);
        entity.setAcknowledgementState(acknowledgementState);
        entity.setCreatedAtUtc(now.minusSeconds(7200));
        entity.setUpdatedAtUtc(now.minusSeconds(7200));
        return entity;
    }
}
