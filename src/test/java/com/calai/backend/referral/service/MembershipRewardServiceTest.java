package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class MembershipRewardServiceTest {

    @Test
    void grantReferralReward_extendsExistingActiveEntitlement() {
        UserEntitlementRepository entitlementRepo = Mockito.mock(UserEntitlementRepository.class);
        MembershipRewardLedgerRepository ledgerRepo = Mockito.mock(MembershipRewardLedgerRepository.class);
        MembershipRewardService service = new MembershipRewardService(entitlementRepo, ledgerRepo);

        UserEntitlementEntity active = new UserEntitlementEntity();
        active.setUserId(10L);
        active.setEntitlementType("YEARLY");
        active.setStatus("ACTIVE");
        active.setValidFromUtc(Instant.parse("2026-04-01T00:00:00Z"));
        active.setValidToUtc(Instant.parse("2026-05-01T00:00:00Z"));

        when(ledgerRepo.existsBySourceTypeAndSourceRefId("REFERRAL_SUCCESS", 99L)).thenReturn(false);
        when(entitlementRepo.findActive(eq(10L), any(Instant.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(active));

        MembershipRewardService.RewardGrantResult result = service.grantReferralReward(10L, 99L);

        assertThat(result.oldPremiumUntil()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(result.newPremiumUntil()).isAfter(result.oldPremiumUntil());

        ArgumentCaptor<UserEntitlementEntity> entitlementCaptor = ArgumentCaptor.forClass(UserEntitlementEntity.class);
        Mockito.verify(entitlementRepo).save(entitlementCaptor.capture());
        assertThat(entitlementCaptor.getValue().getValidToUtc()).isEqualTo(result.newPremiumUntil());
    }

    @Test
    void grantReferralReward_createsNewPremiumWindowWhenNoActiveEntitlement() {
        UserEntitlementRepository entitlementRepo = Mockito.mock(UserEntitlementRepository.class);
        MembershipRewardLedgerRepository ledgerRepo = Mockito.mock(MembershipRewardLedgerRepository.class);
        MembershipRewardService service = new MembershipRewardService(entitlementRepo, ledgerRepo);

        when(ledgerRepo.existsBySourceTypeAndSourceRefId("REFERRAL_SUCCESS", 123L)).thenReturn(false);
        when(entitlementRepo.findActive(eq(7L), any(Instant.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of());

        MembershipRewardService.RewardGrantResult result = service.grantReferralReward(7L, 123L);

        ArgumentCaptor<UserEntitlementEntity> captor = ArgumentCaptor.forClass(UserEntitlementEntity.class);
        Mockito.verify(entitlementRepo).save(captor.capture());
        assertThat(captor.getValue().getEntitlementType()).isEqualTo("MONTHLY");
        assertThat(result.oldPremiumUntil()).isNull();
        assertThat(result.newPremiumUntil()).isAfter(result.grantedAtUtc());
    }
}
