package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.GooglePlaySubscriptionDeferralService;
import com.calai.backend.entitlement.service.PurchaseTokenCrypto;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class MembershipRewardServiceFailureScenarioTest {

    private static final String SOURCE_TYPE_REFERRAL_SUCCESS = "REFERRAL_SUCCESS";
    private static final String GRANT_STATUS_SUCCESS = "SUCCESS";
    private static final String CHANNEL_GOOGLE_PLAY_DEFER = "GOOGLE_PLAY_DEFER";

    @Mock
    private UserEntitlementRepository entitlementRepository;

    @Mock
    private MembershipRewardLedgerRepository rewardLedgerRepository;

    @Mock
    private PurchaseTokenCrypto purchaseTokenCrypto;

    @Mock
    private ObjectProvider<GooglePlaySubscriptionDeferralService> googleDeferralProvider;

    @Mock
    private GooglePlaySubscriptionDeferralService googleDeferralService;

    @Mock
    private MembershipRewardAttemptTxService attemptTxService;

    @Test
    void grantReferralReward_whenGoogleDeferralServiceMissing_shouldMarkRetryableAndNotFallbackBackendOnly() {
        MembershipRewardService service = service();
        UserEntitlementEntity googlePaid = googlePaid();
        stubNoSuccess(99L);
        when(entitlementRepository.findAnyActivePaidGooglePlayForReferral(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of(googlePaid));
        when(googleDeferralProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.grantReferralReward(10L, 99L))
                .isInstanceOf(MembershipRewardService.RewardGrantDeferredException.class)
                .hasMessageContaining("GOOGLE_DEFER_SERVICE_UNAVAILABLE");

        ArgumentCaptor<MembershipRewardLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(MembershipRewardLedgerEntity.class);
        verify(rewardLedgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getRewardChannel()).isEqualTo(CHANNEL_GOOGLE_PLAY_DEFER);
        assertThat(ledgerCaptor.getValue().getGrantStatus()).isEqualTo("FAILED_RETRYABLE");
        assertThat(ledgerCaptor.getValue().getGoogleDeferStatus()).isEqualTo("FAILED_RETRYABLE");
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void grantReferralReward_whenGoogleDeferRetryableFailure_shouldUpdateInProgressLedgerAsFailedRetryable() {
        MembershipRewardService service = service();
        UserEntitlementEntity googlePaid = googlePaid();
        MembershipRewardLedgerEntity inProgress = inProgressLedger();
        stubGooglePaidFlow(99L, googlePaid, inProgress);
        when(googleDeferralService.deferBy30Days("raw-token"))
                .thenThrow(new GooglePlaySubscriptionDeferralService.DeferralException(
                        "GOOGLE_PLAY_DEFER_HTTP_500",
                        true,
                        500,
                        "request-json",
                        "response-json",
                        null
                ));

        assertThatThrownBy(() -> service.grantReferralReward(10L, 99L))
                .isInstanceOf(MembershipRewardService.RewardGrantDeferredException.class)
                .hasMessageContaining("GOOGLE_PLAY_DEFER_HTTP_500");

        verify(attemptTxService).markGoogleDeferFailure(
                eq(777L),
                eq("FAILED_RETRYABLE"),
                eq("FAILED_RETRYABLE"),
                eq("request-json"),
                eq("response-json"),
                eq(500),
                eq("GOOGLE_PLAY_DEFER_HTTP_500"),
                any(),
                any(Instant.class),
                any(Instant.class)
        );
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void grantReferralReward_whenGoogleDeferFinalFailure_shouldUpdateInProgressLedgerAsFailedFinal() {
        MembershipRewardService service = service();
        UserEntitlementEntity googlePaid = googlePaid();
        MembershipRewardLedgerEntity inProgress = inProgressLedger();
        stubGooglePaidFlow(99L, googlePaid, inProgress);
        when(googleDeferralService.deferBy30Days("raw-token"))
                .thenThrow(new GooglePlaySubscriptionDeferralService.DeferralException(
                        "GOOGLE_PLAY_DEFER_HTTP_400",
                        false,
                        400,
                        "request-json",
                        "response-json",
                        null
                ));

        assertThatThrownBy(() -> service.grantReferralReward(10L, 99L))
                .isInstanceOf(MembershipRewardService.RewardGrantFinalException.class)
                .hasMessageContaining("GOOGLE_PLAY_DEFER_HTTP_400");

        verify(attemptTxService).markGoogleDeferFailure(
                eq(777L),
                eq("FAILED_FINAL"),
                eq("FAILED_FINAL"),
                eq("request-json"),
                eq("response-json"),
                eq(400),
                eq("GOOGLE_PLAY_DEFER_HTTP_400"),
                any(),
                eq(null),
                any(Instant.class)
        );
    }

    @Test
    void grantReferralReward_whenExistingSuccessLedger_shouldReturnExistingResultWithoutCallingGoogle() throws Exception {
        MembershipRewardService service = service();
        MembershipRewardLedgerEntity success = new MembershipRewardLedgerEntity();
        success.setOldPremiumUntil(Instant.parse("2026-05-01T00:00:00Z"));
        success.setNewPremiumUntil(Instant.parse("2026-05-31T00:00:00Z"));
        success.setGrantedAtUtc(Instant.parse("2026-05-10T00:00:00Z"));
        when(rewardLedgerRepository.findTopBySourceTypeAndSourceRefIdAndGrantStatusOrderByGrantedAtUtcDesc(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                99L,
                GRANT_STATUS_SUCCESS
        )).thenReturn(Optional.of(success));

        MembershipRewardService.RewardGrantResult result = service.grantReferralReward(10L, 99L);

        assertThat(result.oldPremiumUntil()).isEqualTo(success.getOldPremiumUntil());
        assertThat(result.newPremiumUntil()).isEqualTo(success.getNewPremiumUntil());
        verify(entitlementRepository, never()).findAnyActivePaidGooglePlayForReferral(any(), any(), any());
        verify(googleDeferralService, never()).deferBy30Days(any());
    }

    private MembershipRewardService service() {
        return new MembershipRewardService(
                entitlementRepository,
                rewardLedgerRepository,
                purchaseTokenCrypto,
                googleDeferralProvider,
                attemptTxService
        );
    }

    private void stubNoSuccess(Long claimId) {
        when(rewardLedgerRepository.findTopBySourceTypeAndSourceRefIdAndGrantStatusOrderByGrantedAtUtcDesc(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                claimId,
                GRANT_STATUS_SUCCESS
        )).thenReturn(Optional.empty());
    }

    private void stubGooglePaidFlow(Long claimId, UserEntitlementEntity googlePaid, MembershipRewardLedgerEntity inProgress) {
        stubNoSuccess(claimId);
        when(entitlementRepository.findAnyActivePaidGooglePlayForReferral(
                eq(10L),
                any(Instant.class),
                eq(PageRequest.of(0, 1))
        )).thenReturn(List.of(googlePaid));
        when(googleDeferralProvider.getIfAvailable()).thenReturn(googleDeferralService);
        when(purchaseTokenCrypto.decryptOrNull("cipher-token")).thenReturn("raw-token");
        when(rewardLedgerRepository.findTopBySourceTypeAndSourceRefIdAndRewardChannelAndGrantStatusOrderByGrantedAtUtcDesc(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                claimId,
                CHANNEL_GOOGLE_PLAY_DEFER,
                "GOOGLE_DEFER_IN_PROGRESS"
        )).thenReturn(Optional.empty());
        when(rewardLedgerRepository.findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcDesc(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                claimId,
                CHANNEL_GOOGLE_PLAY_DEFER
        )).thenReturn(Optional.empty());
        when(rewardLedgerRepository.findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByAttemptNoDesc(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                claimId,
                CHANNEL_GOOGLE_PLAY_DEFER
        )).thenReturn(Optional.empty());
        when(attemptTxService.saveNewAttempt(any(MembershipRewardLedgerEntity.class))).thenReturn(inProgress);
    }

    private UserEntitlementEntity googlePaid() {
        UserEntitlementEntity entity = new UserEntitlementEntity();
        entity.setUserId(10L);
        entity.setEntitlementType("YEARLY");
        entity.setStatus("ACTIVE");
        entity.setSource("GOOGLE_PLAY");
        entity.setValidFromUtc(Instant.parse("2026-04-01T00:00:00Z"));
        entity.setValidToUtc(Instant.parse("2026-05-01T00:00:00Z"));
        entity.setPurchaseTokenHash("token-hash");
        entity.setPurchaseTokenCiphertext("cipher-token");
        return entity;
    }

    private MembershipRewardLedgerEntity inProgressLedger() {
        MembershipRewardLedgerEntity ledger = new MembershipRewardLedgerEntity();
        ledger.setId(777L);
        ledger.setUserId(10L);
        ledger.setSourceType(SOURCE_TYPE_REFERRAL_SUCCESS);
        ledger.setSourceRefId(99L);
        ledger.setGrantStatus("GOOGLE_DEFER_IN_PROGRESS");
        ledger.setRewardChannel(CHANNEL_GOOGLE_PLAY_DEFER);
        ledger.setGoogleDeferStatus("IN_PROGRESS");
        ledger.setOldPremiumUntil(Instant.parse("2026-05-01T00:00:00Z"));
        return ledger;
    }
}
