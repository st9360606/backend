package com.caloshape.backend.entitlement.service;

import com.caloshape.backend.entitlement.dto.EntitlementSyncRequest;
import com.caloshape.backend.entitlement.entity.EntitlementTransferAuditEntity;
import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.EntitlementTransferAuditRepository;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import com.caloshape.backend.referral.service.ReferralBillingBridgeService;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private UserRepo userRepo;

    @Mock
    private EntitlementTransferAuditRepository entitlementTransferAuditRepository;

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
                purchaseTokenCrypto,
                userRepo,
                entitlementTransferAuditRepository
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

        UserEntitlementEntity previous =
                new UserEntitlementEntity();
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

    @Test
    void sync_shouldTransferDeletedOwnersActiveGooglePlayEntitlementAndKeepGoogleExpiry() throws Exception {
        String token = "restore-token";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        Instant googleExpiry = Instant.parse("2027-05-21T00:00:00Z");

        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                true,
                "yearly.product",
                googleExpiry,
                false,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                true,
                "BASE",
                "order-restore",
                null,
                false,
                false
        ));

        UserEntitlementEntity existing = new UserEntitlementEntity();
        existing.setId("existing-entitlement-id");
        existing.setUserId(100L);
        existing.setSource("GOOGLE_PLAY");
        existing.setEntitlementType("YEARLY");
        existing.setStatus("ACTIVE");
        existing.setValidFromUtc(Instant.parse("2026-05-21T00:00:00Z"));
        existing.setValidToUtc(googleExpiry);
        existing.setPurchaseTokenHash(tokenHash);
        existing.setCreatedAtUtc(Instant.parse("2026-05-21T00:00:00Z"));
        existing.setUpdatedAtUtc(Instant.parse("2026-05-21T00:00:00Z"));

        User deletedOwner = new User();
        deletedOwner.setId(100L);
        deletedOwner.setStatus("DELETED");

        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(tokenHash))
                .thenReturn(Optional.of(existing));
        when(userRepo.findById(100L)).thenReturn(Optional.of(deletedOwner));
        when(purchaseTokenCrypto.encryptOrNull(token)).thenReturn("cipher-restore-token");
        when(entitlementRepo.save(any(UserEntitlementEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseAcknowledger.acknowledgeWithRetry(
                eq("yearly.product"),
                eq(token),
                eq("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED")
        )).thenReturn(false);

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("yearly.product", token)
        )));

        ArgumentCaptor<UserEntitlementEntity> entitlementCaptor = ArgumentCaptor.forClass(UserEntitlementEntity.class);
        verify(entitlementRepo).save(entitlementCaptor.capture());

        UserEntitlementEntity saved = entitlementCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(200L);
        assertThat(saved.getValidToUtc()).isEqualTo(googleExpiry);
        assertThat(saved.getEntitlementType()).isEqualTo("YEARLY");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");

        ArgumentCaptor<EntitlementTransferAuditEntity> auditCaptor = ArgumentCaptor.forClass(EntitlementTransferAuditEntity.class);
        verify(entitlementTransferAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldUserId()).isEqualTo(100L);
        assertThat(auditCaptor.getValue().getNewUserId()).isEqualTo(200L);
        assertThat(auditCaptor.getValue().getValidToUtc()).isEqualTo(googleExpiry);

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
    void sync_shouldKeepExistingDevFakeExpiryWhenDeletedOwnerRestoreReverifiesLater() throws Exception {
        String token = "fake-dev-sub::yearly.product::paid::1779497595054";
        String tokenHash = EntitlementSyncService.sha256Hex(token);
        Instant originalExpiry = Instant.parse("2027-05-23T00:53:15.054628Z");
        Instant driftedDevVerifierExpiry = Instant.parse("2027-05-23T03:37:52.579282Z");

        when(verifier.verify(token)).thenReturn(new SubscriptionVerifier.VerifiedSubscription(
                true,
                "yearly.product",
                driftedDevVerifierExpiry,
                false,
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                true,
                "BASE",
                "DEV-FAKE-ORDER-1779507472579",
                null,
                true,
                false
        ));

        UserEntitlementEntity existing = new UserEntitlementEntity();
        existing.setId("existing-dev-fake-entitlement-id");
        existing.setUserId(100L);
        existing.setSource("GOOGLE_PLAY");
        existing.setEntitlementType("YEARLY");
        existing.setStatus("ACTIVE");
        existing.setValidFromUtc(Instant.parse("2026-05-23T00:53:15.054084Z"));
        existing.setValidToUtc(originalExpiry);
        existing.setPurchaseTokenHash(tokenHash);
        existing.setCreatedAtUtc(Instant.parse("2026-05-22T18:44:55.517418Z"));
        existing.setUpdatedAtUtc(Instant.parse("2026-05-23T00:53:15.081685Z"));

        User deletedOwner = new User();
        deletedOwner.setId(100L);
        deletedOwner.setStatus("DELETED");

        when(entitlementRepo.findTopByPurchaseTokenHashOrderByUpdatedAtUtcDesc(tokenHash))
                .thenReturn(Optional.of(existing));
        when(userRepo.findById(100L)).thenReturn(Optional.of(deletedOwner));
        when(purchaseTokenCrypto.encryptOrNull(token)).thenReturn("cipher-dev-fake-token");
        when(entitlementRepo.save(any(UserEntitlementEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseAcknowledger.acknowledgeWithRetry(
                eq("yearly.product"),
                eq(token),
                eq("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED")
        )).thenReturn(false);

        service.sync(200L, new EntitlementSyncRequest(List.of(
                new EntitlementSyncRequest.PurchaseTokenPayload("yearly.product", token)
        )));

        ArgumentCaptor<UserEntitlementEntity> entitlementCaptor = ArgumentCaptor.forClass(UserEntitlementEntity.class);
        verify(entitlementRepo).save(entitlementCaptor.capture());

        UserEntitlementEntity saved = entitlementCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(200L);
        assertThat(saved.getValidToUtc()).isEqualTo(originalExpiry);

        ArgumentCaptor<EntitlementTransferAuditEntity> auditCaptor = ArgumentCaptor.forClass(EntitlementTransferAuditEntity.class);
        verify(entitlementTransferAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getValidToUtc()).isEqualTo(originalExpiry);
    }

}
