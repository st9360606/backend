package com.calai.backend.referral.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.domain.ReferralClaimStatus;
import com.calai.backend.referral.domain.ReferralRejectReason;
import com.calai.backend.referral.entity.ReferralClaimEntity;
import com.calai.backend.referral.repo.ReferralClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralClaimServiceScenarioTest {

    @Mock
    private ReferralCodeService referralCodeService;

    @Mock
    private ReferralClaimRepository claimRepository;

    @Mock
    private UserEntitlementRepository entitlementRepository;

    @InjectMocks
    private ReferralClaimService service;

    @Test
    void claim_shouldRejectInvalidPromoCodeWithoutCreatingClaim() {
        when(referralCodeService.findInviterByPromoCode("BADCODE")).thenReturn(null);

        assertThatThrownBy(() -> service.claim(200L, " badcode "))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo(ReferralRejectReason.INVALID_PROMO_CODE.name());
                });

        verify(claimRepository, never()).save(any());
    }

    @Test
    void claim_shouldRejectSelfReferralWithoutCreatingClaim() {
        when(referralCodeService.findInviterByPromoCode("SELF123")).thenReturn(200L);

        assertThatThrownBy(() -> service.claim(200L, "SELF123"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo(ReferralRejectReason.SELF_REFERRAL.name());
                });

        verify(claimRepository, never()).save(any());
    }

    @Test
    void claim_shouldRejectInviteeAlreadyClaimedWithoutCreatingSecondClaim() {
        ReferralClaimEntity existing = new ReferralClaimEntity();
        existing.setId(1L);
        existing.setInviteeUserId(200L);
        existing.setInviterUserId(100L);

        when(referralCodeService.findInviterByPromoCode("USED123")).thenReturn(101L);
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.claim(200L, "USED123"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo(ReferralRejectReason.INVITEE_ALREADY_CLAIMED.name());
                });

        verify(claimRepository, never()).save(any());
    }

    @Test
    void claim_shouldRejectInviteeWithExistingGooglePlayPaidHistory() {
        when(referralCodeService.findInviterByPromoCode("PAID123")).thenReturn(101L);
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.empty());
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(200L)).thenReturn(true);

        assertThatThrownBy(() -> service.claim(200L, "PAID123"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo(ReferralRejectReason.INVITEE_ALREADY_SUBSCRIBED.name());
                });

        verify(claimRepository, never()).save(any());
    }

    @Test
    void claim_shouldCreatePendingSubscriptionClaimForValidNewInvitee() {
        when(referralCodeService.findInviterByPromoCode("GOOD123")).thenReturn(101L);
        when(claimRepository.findByInviteeUserId(200L)).thenReturn(Optional.empty());
        when(entitlementRepository.existsAnyGooglePlayPaidSubscriptionHistory(200L)).thenReturn(false);

        service.claim(200L, "GOOD123");

        ArgumentCaptor<ReferralClaimEntity> captor = ArgumentCaptor.forClass(ReferralClaimEntity.class);
        verify(claimRepository).save(captor.capture());

        ReferralClaimEntity saved = captor.getValue();
        assertThat(saved.getInviterUserId()).isEqualTo(101L);
        assertThat(saved.getInviteeUserId()).isEqualTo(200L);
        assertThat(saved.getPromoCode()).isEqualTo("GOOD123");
        assertThat(saved.getStatus()).isEqualTo(ReferralClaimStatus.PENDING_SUBSCRIPTION.name());
        assertThat(saved.getRejectReason()).isEqualTo(ReferralRejectReason.NONE.name());
    }
}
