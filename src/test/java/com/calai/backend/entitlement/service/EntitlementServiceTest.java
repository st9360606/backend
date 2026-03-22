package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class EntitlementServiceTest {

    @Test
    void resolveTier_should_return_none_when_no_active_entitlement() {
        UserEntitlementRepository repo = Mockito.mock(UserEntitlementRepository.class);
        EntitlementService service = new EntitlementService(repo);

        when(repo.findActive(eq(1L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        EntitlementService.Tier result =
                service.resolveTier(1L, Instant.parse("2026-03-03T00:00:00Z"));

        assertThat(result).isEqualTo(EntitlementService.Tier.NONE);
    }

    @Test
    void resolveTier_should_pick_highest_rank() {
        UserEntitlementRepository repo = Mockito.mock(UserEntitlementRepository.class);
        EntitlementService service = new EntitlementService(repo);

        UserEntitlementEntity trial = Mockito.mock(UserEntitlementEntity.class);
        when(trial.getId()).thenReturn(String.valueOf(10L));
        when(trial.getEntitlementType()).thenReturn("TRIAL");

        UserEntitlementEntity yearly = Mockito.mock(UserEntitlementEntity.class);
        when(yearly.getId()).thenReturn(String.valueOf(20L));
        when(yearly.getEntitlementType()).thenReturn("YEARLY");

        when(repo.findActive(eq(1L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(trial, yearly));

        EntitlementService.Tier result =
                service.resolveTier(1L, Instant.parse("2026-03-03T00:00:00Z"));

        assertThat(result).isEqualTo(EntitlementService.Tier.YEARLY);
    }

    @Test
    void resolveTier_should_ignore_unknown_type_but_keep_known_type() {
        UserEntitlementRepository repo = Mockito.mock(UserEntitlementRepository.class);
        EntitlementService service = new EntitlementService(repo);

        UserEntitlementEntity unknown = Mockito.mock(UserEntitlementEntity.class);
        when(unknown.getId()).thenReturn(String.valueOf(30L));
        when(unknown.getEntitlementType()).thenReturn("PREMIUM_PLUS");

        UserEntitlementEntity monthly = Mockito.mock(UserEntitlementEntity.class);
        when(monthly.getId()).thenReturn(String.valueOf(40L));
        when(monthly.getEntitlementType()).thenReturn("MONTHLY");

        when(repo.findActive(eq(1L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(unknown, monthly));

        EntitlementService.Tier result =
                service.resolveTier(1L, Instant.parse("2026-03-03T00:00:00Z"));

        assertThat(result).isEqualTo(EntitlementService.Tier.MONTHLY);
    }
}