package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.service.ReferralBillingBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementSyncServiceReferralScenarioTest {

    @Mock
    private SubscriptionVerifier verifier;

    @Mock
    private UserEntitlementRepository entitlementRepo;

    @Mock
    private ReferralBillingBridgeService referralBillingBridgeService;

    @Mock
    private PurchaseAcknowledger purchaseAcknowledger;

    @Mock
    private PurchaseTokenCrypto purchaseTokenCrypto;

    private BillingProductProperties productProps;
    private EntitlementSyncService service;

    @BeforeEach
    void setUp() {
        productProps = new BillingProductProperties();
        productProps.setMonthly(Set.of("monthly.product"));
        productProps.setYearly(Set.of("yearly.product"));

        service = new EntitlementSyncService(
                verifier,
                entitlementRepo,
                productProps,
                referralBillingBridgeService,
                purchaseAcknowledger,
                purchaseTokenCrypto
        );

        when(entitlementRepo.findActiveBestFirst(any(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of());
    }

    @Test
    void sync_shouldRecordPendingReferralPurchaseBeforeInactiveBranchContinues() throws Exception {
        String token = "pending-token";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
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

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("monthly.product", token)
        )));

        verify(referralBillingBridgeService).onFirstPaidSubscriptionVerified(
                eq(200L),
                eq(tokenHash),
                any(Instant.class),
                eq(true),
                eq(true),
                eq(false)
        );
        verify(entitlementRepo).closeActiveByPurchaseTokenHash(
                eq(tokenHash),
                eq("EXPIRED"),
                any(Instant.class),
                eq(null),
                any(Instant.class),
                eq("SUBSCRIPTION_STATE_PENDING"),
                eq("PENDING"),
                eq("GOOGLE_PLAY_PENDING_PURCHASE"),
                any(Instant.class)
        );
    }

    @Test
    void sync_shouldNotConvertPendingPurchaseCanceledBackToPendingReferral() throws Exception {
        String token = "pending-canceled-token";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                false,
                "monthly.product",
                null,
                false,
                "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                false,
                "BASE",
                "order-canceled",
                null,
                false,
                false
        ));

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("monthly.product", token)
        )));

        verify(referralBillingBridgeService, never()).onFirstPaidSubscriptionVerified(
                any(),
                anyString(),
                any(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        );
        verify(entitlementRepo).closeActiveByPurchaseTokenHash(
                eq(tokenHash),
                eq("EXPIRED"),
                any(Instant.class),
                eq(null),
                any(Instant.class),
                eq("SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"),
                eq("PENDING_PURCHASE_CANCELED"),
                eq("GOOGLE_PLAY_PENDING_PURCHASE_CANCELED"),
                any(Instant.class)
        );
    }

    @Test
    void sync_shouldStartVerificationWindowForFirstValidPaidPurchase() throws Exception {
        String token = "paid-token";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        Instant expiry = Instant.parse("2026-06-01T00:00:00Z");

        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                true,
                "monthly.product",
                expiry,
                false,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                true,
                "BASE",
                "order-paid",
                null,
                false,
                false
        ));
        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(tokenHash))
                .thenReturn(Optional.empty());
        when(entitlementRepo.existsAnyGooglePlayPaidSubscriptionHistory(200L))
                .thenReturn(false);
        when(purchaseTokenCrypto.encryptOrNull(token)).thenReturn("cipher-paid-token");
        when(entitlementRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseAcknowledger.acknowledgeWithRetry(
                eq("monthly.product"),
                eq(token),
                eq("ACKNOWLEDGEMENT_STATE_PENDING")
        )).thenReturn(false);

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("monthly.product", token)
        )));

        verify(referralBillingBridgeService).onFirstPaidSubscriptionVerified(
                eq(200L),
                eq(tokenHash),
                any(Instant.class),
                eq(true),
                eq(false),
                eq(false)
        );
    }

    @Test
    void sync_shouldNotStartVerificationWindowForFreeTrial() throws Exception {
        String token = "trial-token";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        Instant expiry = Instant.parse("2026-05-05T00:00:00Z");

        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                true,
                "monthly.product",
                expiry,
                true,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                true,
                "FREE_TRIAL",
                "order-trial",
                null,
                false,
                false
        ));
        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(tokenHash))
                .thenReturn(Optional.empty());
        when(entitlementRepo.existsAnyGooglePlayPaidSubscriptionHistory(200L))
                .thenReturn(false);
        when(purchaseTokenCrypto.encryptOrNull(token)).thenReturn("cipher-trial-token");
        when(entitlementRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseAcknowledger.acknowledgeWithRetry(anyString(), anyString(), any()))
                .thenReturn(false);

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("monthly.product", token)
        )));

        verify(referralBillingBridgeService, never()).onFirstPaidSubscriptionVerified(
                any(),
                anyString(),
                any(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        );
    }

    @Test
    void syncPurchaseTokenFromRtdn_shouldRecordLinkedPendingTokenWithoutClosingPreviousEntitlement() throws Exception {
        String newToken = "new-pending-token";
        String oldToken = "old-active-token";
        String newHash = EntitlementSyncService.sha256Hex(newToken);
        String oldHash = EntitlementSyncService.sha256Hex(oldToken);

        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(newHash))
                .thenReturn(Optional.empty());
        when(verifier.verify(newToken)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                false,
                "monthly.product",
                null,
                false,
                "SUBSCRIPTION_STATE_PENDING",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                true,
                "BASE",
                "order-linked-pending",
                oldToken,
                false,
                true
        ));

        com.calai.backend.entitlement.entity.UserEntitlementEntity previous =
                new com.calai.backend.entitlement.entity.UserEntitlementEntity();
        previous.setId("old-entitlement-id");
        previous.setUserId(200L);
        previous.setPurchaseTokenHash(oldHash);

        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(oldHash))
                .thenReturn(Optional.of(previous));

        service.syncPurchaseTokenFromRtdn(newToken, Instant.parse("2026-05-02T00:00:00Z"));

        verify(referralBillingBridgeService).onFirstPaidSubscriptionVerified(
                eq(200L),
                eq(newHash),
                any(Instant.class),
                eq(true),
                eq(true),
                eq(false)
        );
        verify(entitlementRepo, never()).closeActiveByPurchaseTokenHash(
                eq(oldHash),
                anyString(),
                any(Instant.class),
                any(),
                any(Instant.class),
                any(),
                any(),
                any(),
                any(Instant.class)
        );
    }
}
