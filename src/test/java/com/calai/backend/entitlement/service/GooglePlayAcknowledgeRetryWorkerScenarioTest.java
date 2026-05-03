package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GooglePlayAcknowledgeRetryWorkerScenarioTest {

    @Mock
    private UserEntitlementRepository entitlementRepository;

    @Mock
    private EntitlementSyncService entitlementSyncService;

    @Mock
    private PurchaseTokenCrypto purchaseTokenCrypto;

    private GooglePlayAcknowledgeRetryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new GooglePlayAcknowledgeRetryWorker(
                entitlementRepository,
                entitlementSyncService,
                purchaseTokenCrypto
        );
        ReflectionTestUtils.setField(worker, "staleAfter", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(worker, "batchSize", 50);
    }

    @Test
    void retryPendingAcknowledgements_shouldSkipWhenTokenCryptoDisabled() {
        when(purchaseTokenCrypto.enabled()).thenReturn(false);

        worker.retryPendingAcknowledgements();

        verify(entitlementRepository, never()).findAckPendingGooglePlayEntitlements(any(), any(Pageable.class));
        verify(entitlementSyncService, never()).retryAcknowledgeForStoredToken(any(), any());
    }

    @Test
    void retryPendingAcknowledgements_shouldRetryRowsReturnedByRepository() {
        when(purchaseTokenCrypto.enabled()).thenReturn(true);

        UserEntitlementEntity first = row("ent-1", 101L);
        UserEntitlementEntity second = row("ent-2", 102L);
        when(entitlementRepository.findAckPendingGooglePlayEntitlements(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        worker.retryPendingAcknowledgements();

        verify(entitlementSyncService).retryAcknowledgeForStoredToken(eq("ent-1"), any(Instant.class));
        verify(entitlementSyncService).retryAcknowledgeForStoredToken(eq("ent-2"), any(Instant.class));
    }

    @Test
    void retryPendingAcknowledgements_shouldContinueWhenOneRowFails() {
        when(purchaseTokenCrypto.enabled()).thenReturn(true);

        UserEntitlementEntity first = row("ent-1", 101L);
        UserEntitlementEntity second = row("ent-2", 102L);
        when(entitlementRepository.findAckPendingGooglePlayEntitlements(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new RuntimeException("temporary failure"))
                .when(entitlementSyncService)
                .retryAcknowledgeForStoredToken(eq("ent-1"), any(Instant.class));

        worker.retryPendingAcknowledgements();

        verify(entitlementSyncService).retryAcknowledgeForStoredToken(eq("ent-1"), any(Instant.class));
        verify(entitlementSyncService).retryAcknowledgeForStoredToken(eq("ent-2"), any(Instant.class));
    }

    private UserEntitlementEntity row(String id, Long userId) {
        UserEntitlementEntity entity = new UserEntitlementEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setSource("GOOGLE_PLAY");
        entity.setStatus("ACTIVE");
        entity.setPurchaseTokenCiphertext("cipher-" + id);
        entity.setAcknowledgementState("ACKNOWLEDGEMENT_STATE_PENDING");
        return entity;
    }
}
