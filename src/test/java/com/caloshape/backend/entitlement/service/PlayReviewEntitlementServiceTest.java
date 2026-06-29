package com.caloshape.backend.entitlement.service;

import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayReviewEntitlementServiceTest {

    @Test
    void createsActiveInternalPremiumEntitlement() {
        UserEntitlementRepository repository = mock(UserEntitlementRepository.class);
        when(repository.findTopByUserIdAndSourceAndProductIdOrderByValidToUtcDesc(
                42L,
                PlayReviewEntitlementService.SOURCE,
                PlayReviewEntitlementService.PRODUCT_ID
        )).thenReturn(Optional.empty());

        Instant now = Instant.parse("2026-06-29T01:00:00Z");
        new PlayReviewEntitlementService(repository)
                .ensureActive(42L, now, Duration.ofDays(3650));

        ArgumentCaptor<UserEntitlementEntity> captor =
                ArgumentCaptor.forClass(UserEntitlementEntity.class);
        verify(repository).save(captor.capture());

        UserEntitlementEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getEntitlementType()).isEqualTo("YEARLY");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSource()).isEqualTo("INTERNAL");
        assertThat(saved.getProductId()).isEqualTo("google_play_review");
        assertThat(saved.getPaymentState()).isEqualTo("OK");
        assertThat(saved.getValidFromUtc()).isEqualTo(now);
        assertThat(saved.getValidToUtc()).isEqualTo(now.plus(Duration.ofDays(3650)));
    }

    @Test
    void doesNotShortenExistingEntitlement() {
        UserEntitlementRepository repository = mock(UserEntitlementRepository.class);
        UserEntitlementEntity existing = new UserEntitlementEntity();
        existing.setValidFromUtc(Instant.parse("2026-01-01T00:00:00Z"));
        existing.setValidToUtc(Instant.parse("2040-01-01T00:00:00Z"));
        when(repository.findTopByUserIdAndSourceAndProductIdOrderByValidToUtcDesc(
                42L,
                PlayReviewEntitlementService.SOURCE,
                PlayReviewEntitlementService.PRODUCT_ID
        )).thenReturn(Optional.of(existing));

        new PlayReviewEntitlementService(repository).ensureActive(
                42L,
                Instant.parse("2026-06-29T01:00:00Z"),
                Duration.ofDays(3650)
        );

        assertThat(existing.getValidToUtc()).isEqualTo(Instant.parse("2040-01-01T00:00:00Z"));
        verify(repository).save(existing);
    }
}
