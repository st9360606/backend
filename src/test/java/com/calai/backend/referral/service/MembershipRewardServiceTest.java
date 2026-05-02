package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.GooglePlaySubscriptionDeferralService;
import com.calai.backend.entitlement.service.PurchaseTokenCrypto;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipRewardServiceTest {

    private static final String SOURCE_TYPE_REFERRAL_SUCCESS = "REFERRAL_SUCCESS";
    private static final String GRANT_STATUS_SUCCESS = "SUCCESS";
    private static final String CHANNEL_GOOGLE_PLAY_DEFER = "GOOGLE_PLAY_DEFER";
    private static final String CHANNEL_BACKEND_ONLY = "BACKEND_ONLY";

    @Test
    void grantReferralReward_createsBackendOnlyReferralRewardWhenNoGooglePlayPaidSubscriptionButHasExistingEntitlement()
            throws MembershipRewardService.RewardGrantDeferredException,
            MembershipRewardService.RewardGrantFinalException {
        Fixture fx = new Fixture();
        fx.stubNoExistingSuccess(99L);
        fx.stubFirstAttempt(99L);

        UserEntitlementEntity active = new UserEntitlementEntity();
        active.setUserId(10L);
        active.setEntitlementType("YEARLY");
        active.setStatus("ACTIVE");
        active.setValidFromUtc(Instant.parse("2026-04-01T00:00:00Z"));
        active.setValidToUtc(Instant.parse("2026-05-01T00:00:00Z"));
        active.setSource("INTERNAL");

        when(fx.entitlementRepo.findAnyActivePaidGooglePlayForReferral(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of());

        when(fx.entitlementRepo.findActiveBestFirst(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of(active));

        MembershipRewardService.RewardGrantResult result =
                fx.service.grantReferralReward(10L, 99L);

        assertThat(result.oldPremiumUntil())
                .isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(result.newPremiumUntil())
                .isAfter(result.oldPremiumUntil());

        ArgumentCaptor<UserEntitlementEntity> entitlementCaptor =
                ArgumentCaptor.forClass(UserEntitlementEntity.class);

        verify(fx.entitlementRepo).save(entitlementCaptor.capture());

        UserEntitlementEntity saved = entitlementCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getEntitlementType()).isEqualTo("REFERRAL_REWARD");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSource()).isEqualTo("REFERRAL_REWARD");
        assertThat(saved.getValidToUtc()).isEqualTo(result.newPremiumUntil());

        ArgumentCaptor<MembershipRewardLedgerEntity> ledgerCaptor =
                ArgumentCaptor.forClass(MembershipRewardLedgerEntity.class);

        verify(fx.ledgerRepo).save(ledgerCaptor.capture());

        MembershipRewardLedgerEntity ledger = ledgerCaptor.getValue();
        assertThat(ledger.getUserId()).isEqualTo(10L);
        assertThat(ledger.getSourceType()).isEqualTo(SOURCE_TYPE_REFERRAL_SUCCESS);
        assertThat(ledger.getSourceRefId()).isEqualTo(99L);
        assertThat(ledger.getAttemptNo()).isEqualTo(1);
        assertThat(ledger.getTraceId()).isNotBlank();
        assertThat(ledger.getGrantStatus()).isEqualTo(GRANT_STATUS_SUCCESS);
        assertThat(ledger.getRewardChannel()).isEqualTo(CHANNEL_BACKEND_ONLY);
        assertThat(ledger.getGoogleDeferStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(ledger.getDaysAdded()).isEqualTo(30);
        assertThat(ledger.getOldPremiumUntil()).isEqualTo(result.oldPremiumUntil());
        assertThat(ledger.getNewPremiumUntil()).isEqualTo(result.newPremiumUntil());
    }

    @Test
    void grantReferralReward_createsBackendOnlyReferralRewardWhenNoActiveEntitlement()
            throws MembershipRewardService.RewardGrantDeferredException,
            MembershipRewardService.RewardGrantFinalException {
        Fixture fx = new Fixture();
        fx.stubNoExistingSuccess(123L);
        fx.stubFirstAttempt(123L);

        when(fx.entitlementRepo.findAnyActivePaidGooglePlayForReferral(
                eq(7L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of());

        when(fx.entitlementRepo.findActiveBestFirst(
                eq(7L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of());

        MembershipRewardService.RewardGrantResult result =
                fx.service.grantReferralReward(7L, 123L);

        ArgumentCaptor<UserEntitlementEntity> entitlementCaptor =
                ArgumentCaptor.forClass(UserEntitlementEntity.class);

        verify(fx.entitlementRepo).save(entitlementCaptor.capture());

        UserEntitlementEntity saved = entitlementCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getEntitlementType()).isEqualTo("REFERRAL_REWARD");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSource()).isEqualTo("REFERRAL_REWARD");

        assertThat(result.oldPremiumUntil()).isNull();
        assertThat(result.newPremiumUntil()).isAfter(result.grantedAtUtc());
    }

    @Test
    void grantReferralReward_defersGooglePlayPaidSubscriptionWhenAvailable()
            throws MembershipRewardService.RewardGrantDeferredException,
            MembershipRewardService.RewardGrantFinalException {
        Fixture fx = new Fixture();
        fx.stubNoExistingSuccess(99L);
        fx.stubFirstAttempt(99L);
        fx.stubNoInProgress(99L);
        fx.stubNoLatestGoogleAttempt(99L);

        UserEntitlementEntity googlePaid = new UserEntitlementEntity();
        googlePaid.setUserId(10L);
        googlePaid.setEntitlementType("YEARLY");
        googlePaid.setStatus("ACTIVE");
        googlePaid.setSource("GOOGLE_PLAY");
        googlePaid.setValidFromUtc(Instant.parse("2026-04-01T00:00:00Z"));
        googlePaid.setValidToUtc(Instant.parse("2026-05-01T00:00:00Z"));
        googlePaid.setPurchaseTokenCiphertext("encrypted-token");
        googlePaid.setPurchaseTokenHash("token-hash");

        Instant googleNewExpiry = Instant.parse("2026-05-31T00:00:00Z");

        when(fx.entitlementRepo.findAnyActivePaidGooglePlayForReferral(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of(googlePaid));

        when(fx.googleDeferralProvider.getIfAvailable())
                .thenReturn(fx.googleDeferralService);

        when(fx.purchaseTokenCrypto.decryptOrNull("encrypted-token"))
                .thenReturn("raw-google-play-token");

        MembershipRewardLedgerEntity inProgressLedger = new MembershipRewardLedgerEntity();
        inProgressLedger.setId(555L);
        inProgressLedger.setUserId(10L);
        inProgressLedger.setSourceType(SOURCE_TYPE_REFERRAL_SUCCESS);
        inProgressLedger.setSourceRefId(99L);
        inProgressLedger.setAttemptNo(1);
        inProgressLedger.setTraceId("trace-555");
        inProgressLedger.setGrantStatus("GOOGLE_DEFER_IN_PROGRESS");
        inProgressLedger.setRewardChannel(CHANNEL_GOOGLE_PLAY_DEFER);
        inProgressLedger.setGoogleDeferStatus("IN_PROGRESS");
        inProgressLedger.setGooglePurchaseTokenHash("token-hash");
        inProgressLedger.setOldPremiumUntil(Instant.parse("2026-05-01T00:00:00Z"));

        when(fx.attemptTxService.saveNewAttempt(any(MembershipRewardLedgerEntity.class)))
                .thenReturn(inProgressLedger);

        when(fx.googleDeferralService.deferBy30Days("raw-google-play-token"))
                .thenReturn(new GooglePlaySubscriptionDeferralService.DeferralResult(
                        googleNewExpiry,
                        "{\"deferralContext\":{\"deferDuration\":\"2592000s\"}}",
                        "{\"itemExpiryTimeDetails\":[{\"expiryTime\":\"2026-05-31T00:00:00Z\"}]}",
                        200
                ));

        MembershipRewardService.RewardGrantResult result =
                fx.service.grantReferralReward(10L, 99L);

        assertThat(result.oldPremiumUntil())
                .isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(result.newPremiumUntil())
                .isEqualTo(googleNewExpiry);

        verify(fx.googleDeferralService).deferBy30Days("raw-google-play-token");

        assertThat(googlePaid.getValidToUtc()).isEqualTo(googleNewExpiry);
        assertThat(googlePaid.getLastGoogleVerifiedAtUtc()).isNotNull();
        verify(fx.attemptTxService).saveEntitlement(googlePaid);

        ArgumentCaptor<MembershipRewardLedgerEntity> inProgressCaptor =
                ArgumentCaptor.forClass(MembershipRewardLedgerEntity.class);

        verify(fx.attemptTxService).saveNewAttempt(inProgressCaptor.capture());

        MembershipRewardLedgerEntity inProgress = inProgressCaptor.getValue();
        assertThat(inProgress.getAttemptNo()).isEqualTo(1);
        assertThat(inProgress.getTraceId()).isNotBlank();
        assertThat(inProgress.getGrantStatus()).isEqualTo("GOOGLE_DEFER_IN_PROGRESS");
        assertThat(inProgress.getRewardChannel()).isEqualTo(CHANNEL_GOOGLE_PLAY_DEFER);
        assertThat(inProgress.getGooglePurchaseTokenHash()).isEqualTo("token-hash");
        assertThat(inProgress.getGoogleDeferStatus()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.getOldPremiumUntil()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));

        verify(fx.attemptTxService).markGoogleDeferSuccess(
                eq(555L),
                eq(googleNewExpiry),
                eq("{\"deferralContext\":{\"deferDuration\":\"2592000s\"}}"),
                eq("{\"itemExpiryTimeDetails\":[{\"expiryTime\":\"2026-05-31T00:00:00Z\"}]}"),
                eq(200),
                any(Instant.class)
        );
    }

    @Test
    void grantReferralReward_doesNotFallbackToBackendOnlyWhenGooglePlayTokenCannotBeDecrypted() {
        Fixture fx = new Fixture();
        fx.stubNoExistingSuccess(99L);
        fx.stubFirstAttempt(99L);
        fx.stubNoInProgress(99L);

        UserEntitlementEntity googlePaid = new UserEntitlementEntity();
        googlePaid.setUserId(10L);
        googlePaid.setEntitlementType("YEARLY");
        googlePaid.setStatus("ACTIVE");
        googlePaid.setSource("GOOGLE_PLAY");
        googlePaid.setValidFromUtc(Instant.parse("2026-04-01T00:00:00Z"));
        googlePaid.setValidToUtc(Instant.parse("2026-05-01T00:00:00Z"));
        googlePaid.setPurchaseTokenCiphertext("bad-encrypted-token");
        googlePaid.setPurchaseTokenHash("token-hash");

        when(fx.entitlementRepo.findAnyActivePaidGooglePlayForReferral(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of(googlePaid));

        when(fx.googleDeferralProvider.getIfAvailable())
                .thenReturn(fx.googleDeferralService);

        when(fx.purchaseTokenCrypto.decryptOrNull("bad-encrypted-token"))
                .thenReturn(null);

        assertThatThrownBy(() -> fx.service.grantReferralReward(10L, 99L))
                .isInstanceOf(MembershipRewardService.RewardGrantFinalException.class)
                .hasMessageContaining("GOOGLE_PURCHASE_TOKEN_NOT_DECRYPTABLE");

        verify(fx.googleDeferralService, never()).deferBy30Days(any());

        ArgumentCaptor<MembershipRewardLedgerEntity> ledgerCaptor =
                ArgumentCaptor.forClass(MembershipRewardLedgerEntity.class);

        verify(fx.ledgerRepo).save(ledgerCaptor.capture());

        MembershipRewardLedgerEntity ledger = ledgerCaptor.getValue();
        assertThat(ledger.getAttemptNo()).isEqualTo(1);
        assertThat(ledger.getTraceId()).isNotBlank();
        assertThat(ledger.getGrantStatus()).isEqualTo("FAILED_FINAL");
        assertThat(ledger.getRewardChannel()).isEqualTo(CHANNEL_GOOGLE_PLAY_DEFER);
        assertThat(ledger.getGooglePurchaseTokenHash()).isEqualTo("token-hash");
        assertThat(ledger.getGoogleDeferStatus()).isEqualTo("FAILED_FINAL");
        assertThat(ledger.getErrorCode()).isEqualTo("GOOGLE_PURCHASE_TOKEN_NOT_DECRYPTABLE");
    }

    @SuppressWarnings("unchecked")
    private static final class Fixture {
        final UserEntitlementRepository entitlementRepo =
                Mockito.mock(UserEntitlementRepository.class);

        final MembershipRewardLedgerRepository ledgerRepo =
                Mockito.mock(MembershipRewardLedgerRepository.class);

        final PurchaseTokenCrypto purchaseTokenCrypto =
                Mockito.mock(PurchaseTokenCrypto.class);

        final ObjectProvider<GooglePlaySubscriptionDeferralService> googleDeferralProvider =
                Mockito.mock(ObjectProvider.class);

        final GooglePlaySubscriptionDeferralService googleDeferralService =
                Mockito.mock(GooglePlaySubscriptionDeferralService.class);

        final MembershipRewardAttemptTxService attemptTxService =
                Mockito.mock(MembershipRewardAttemptTxService.class);

        final MembershipRewardService service =
                new MembershipRewardService(
                        entitlementRepo,
                        ledgerRepo,
                        purchaseTokenCrypto,
                        googleDeferralProvider,
                        attemptTxService
                );

        void stubNoExistingSuccess(Long claimId) {
            when(ledgerRepo.findTopBySourceTypeAndSourceRefIdAndGrantStatusOrderByGrantedAtUtcDesc(
                    SOURCE_TYPE_REFERRAL_SUCCESS,
                    claimId,
                    GRANT_STATUS_SUCCESS
            )).thenReturn(Optional.empty());
        }

        void stubFirstAttempt(Long claimId) {
            when(ledgerRepo.findTopBySourceTypeAndSourceRefIdOrderByAttemptNoDesc(
                    SOURCE_TYPE_REFERRAL_SUCCESS,
                    claimId
            )).thenReturn(Optional.empty());

            when(ledgerRepo.findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByAttemptNoDesc(
                    SOURCE_TYPE_REFERRAL_SUCCESS,
                    claimId,
                    CHANNEL_GOOGLE_PLAY_DEFER
            )).thenReturn(Optional.empty());
        }

        void stubNoInProgress(Long claimId) {
            when(ledgerRepo.findTopBySourceTypeAndSourceRefIdAndRewardChannelAndGrantStatusOrderByGrantedAtUtcDesc(
                    SOURCE_TYPE_REFERRAL_SUCCESS,
                    claimId,
                    CHANNEL_GOOGLE_PLAY_DEFER,
                    "GOOGLE_DEFER_IN_PROGRESS"
            )).thenReturn(Optional.empty());
        }

        void stubNoLatestGoogleAttempt(Long claimId) {
            when(ledgerRepo.findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcDesc(
                    SOURCE_TYPE_REFERRAL_SUCCESS,
                    claimId,
                    CHANNEL_GOOGLE_PLAY_DEFER
            )).thenReturn(Optional.empty());
        }
    }
}
