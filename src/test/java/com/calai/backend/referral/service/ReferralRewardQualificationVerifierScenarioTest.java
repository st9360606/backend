package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.BillingProductProperties;
import com.calai.backend.entitlement.service.PurchaseTokenCrypto;
import com.calai.backend.entitlement.service.SubscriptionVerifier;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralRewardQualificationVerifierScenarioTest {

    @Mock
    private UserEntitlementRepository entitlementRepository;

    @Mock
    private PurchaseTokenCrypto purchaseTokenCrypto;

    @Mock
    private SubscriptionVerifier subscriptionVerifier;

    @Mock
    private ObjectProvider<VoidedPurchaseChecker> voidedPurchaseCheckerProvider;

    @Mock
    private VoidedPurchaseChecker voidedPurchaseChecker;

    private BillingProductProperties productProps;
    private ReferralRewardQualificationVerifier verifier;

    private final Instant now = Instant.parse("2026-05-10T00:00:00Z");

    @BeforeEach
    void setUp() {
        productProps = new BillingProductProperties();
        productProps.setMonthly(Set.of("monthly.product"));
        productProps.setYearly(Set.of("yearly.product"));

        verifier = new ReferralRewardQualificationVerifier(
                entitlementRepository,
                purchaseTokenCrypto,
                subscriptionVerifier,
                productProps,
                voidedPurchaseCheckerProvider
        );
    }

    @Test
    void verifyBeforeReward_shouldRejectWhenClaimHasNoPurchaseTokenHash() {
        ReferralClaimEntity claim = claim();
        claim.setPurchaseTokenHash(null);

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(ReferralRejectReason.PURCHASE_NOT_VERIFIED.name());
    }

    @Test
    void verifyBeforeReward_shouldRejectPendingPurchaseAtFinalVerification() throws Exception {
        ReferralClaimEntity claim = claim();
        UserEntitlementEntity entitlement = entitlementForInvitee();
        when(entitlementRepository.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc("invitee-token-hash"))
                .thenReturn(Optional.of(entitlement));
        when(purchaseTokenCrypto.decryptOrNull("cipher-invitee-token"))
                .thenReturn("raw-invitee-token");
        when(subscriptionVerifier.verify("raw-invitee-token"))
                .thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                        false,
                        "monthly.product",
                        null,
                        false,
                        "SUBSCRIPTION_STATE_PENDING",
                        "ACKNOWLEDGEMENT_STATE_PENDING",
                        true,
                        "BASE",
                        "order-pending",
                        null,
                        false,
                        true
                ));

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(ReferralRejectReason.PURCHASE_PENDING.name());
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void verifyBeforeReward_shouldRejectTestPurchase() throws Exception {
        ReferralClaimEntity claim = claim();
        UserEntitlementEntity entitlement = entitlementForInvitee();
        when(entitlementRepository.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc("invitee-token-hash"))
                .thenReturn(Optional.of(entitlement));
        when(purchaseTokenCrypto.decryptOrNull("cipher-invitee-token"))
                .thenReturn("raw-invitee-token");
        when(subscriptionVerifier.verify("raw-invitee-token"))
                .thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                        true,
                        "monthly.product",
                        now.plusSeconds(86400),
                        false,
                        "SUBSCRIPTION_STATE_ACTIVE",
                        "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                        true,
                        "BASE",
                        "order-test",
                        null,
                        true,
                        false
                ));

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(ReferralRejectReason.TEST_PURCHASE.name());
    }

    @Test
    void verifyBeforeReward_shouldRejectVoidedPurchaseFoundByFinalAudit() throws Exception {
        ReferralClaimEntity claim = claim();
        UserEntitlementEntity entitlement = entitlementForInvitee();
        when(entitlementRepository.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc("invitee-token-hash"))
                .thenReturn(Optional.of(entitlement));
        when(purchaseTokenCrypto.decryptOrNull("cipher-invitee-token"))
                .thenReturn("raw-invitee-token");
        when(subscriptionVerifier.verify("raw-invitee-token"))
                .thenReturn(activePaidSubscription());
        when(voidedPurchaseCheckerProvider.getIfAvailable()).thenReturn(voidedPurchaseChecker);
        when(voidedPurchaseChecker.isVoidedSubscriptionPurchase(
                eq("raw-invitee-token"),
                eq(claim.getSubscribedAtUtc()),
                eq(now)
        )).thenReturn(true);

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.rejectReason()).isEqualTo(ReferralRejectReason.REFUNDED_OR_REVOKED.name());
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void verifyBeforeReward_shouldRetryLaterWhenGoogleVerificationFailsTemporarily() throws Exception {
        ReferralClaimEntity claim = claim();
        UserEntitlementEntity entitlement = entitlementForInvitee();
        when(entitlementRepository.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc("invitee-token-hash"))
                .thenReturn(Optional.of(entitlement));
        when(purchaseTokenCrypto.decryptOrNull("cipher-invitee-token"))
                .thenReturn("raw-invitee-token");
        when(subscriptionVerifier.verify("raw-invitee-token"))
                .thenThrow(new RuntimeException("google 500"));

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void verifyBeforeReward_shouldSucceedAndRefreshEntitlementWhenPaidSubscriptionIsValid() throws Exception {
        ReferralClaimEntity claim = claim();
        UserEntitlementEntity entitlement = entitlementForInvitee();
        when(entitlementRepository.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc("invitee-token-hash"))
                .thenReturn(Optional.of(entitlement));
        when(purchaseTokenCrypto.decryptOrNull("cipher-invitee-token"))
                .thenReturn("raw-invitee-token");
        when(subscriptionVerifier.verify("raw-invitee-token"))
                .thenReturn(activePaidSubscription());
        when(voidedPurchaseCheckerProvider.getIfAvailable()).thenReturn(null);

        ReferralRewardQualificationVerifier.VerificationResult result =
                verifier.verifyBeforeReward(claim, now);

        assertThat(result.qualified()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(result.rejectReason()).isNull();
        assertThat(entitlement.getValidToUtc()).isEqualTo(now.plusSeconds(30L * 24L * 3600L));
        assertThat(entitlement.getSubscriptionState()).isEqualTo("SUBSCRIPTION_STATE_ACTIVE");
        verify(entitlementRepository).save(entitlement);
    }

    private ReferralClaimEntity claim() {
        ReferralClaimEntity claim = new ReferralClaimEntity();
        claim.setId(10L);
        claim.setInviterUserId(100L);
        claim.setInviteeUserId(200L);
        claim.setPromoCode("GOOD123");
        claim.setStatus("PENDING_VERIFICATION");
        claim.setPurchaseTokenHash("invitee-token-hash");
        claim.setSubscribedAtUtc(Instant.parse("2026-05-02T00:00:00Z"));
        return claim;
    }

    private UserEntitlementEntity entitlementForInvitee() {
        UserEntitlementEntity entitlement = new UserEntitlementEntity();
        entitlement.setId("entitlement-id");
        entitlement.setUserId(200L);
        entitlement.setPurchaseTokenHash("invitee-token-hash");
        entitlement.setPurchaseTokenCiphertext("cipher-invitee-token");
        entitlement.setEntitlementType("MONTHLY");
        entitlement.setSource("GOOGLE_PLAY");
        entitlement.setStatus("ACTIVE");
        entitlement.setValidFromUtc(Instant.parse("2026-05-02T00:00:00Z"));
        entitlement.setValidToUtc(now.plusSeconds(86400));
        return entitlement;
    }

    private SubscriptionVerifier.VerifiedSubscription activePaidSubscription() {
        return new SubscriptionVerifier.VerifiedSubscription(
                true,
                "monthly.product",
                now.plusSeconds(30L * 24L * 3600L),
                false,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                true,
                "BASE",
                "order-active",
                null,
                false,
                false
        );
    }
}
