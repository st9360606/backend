package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class GooglePlayAcknowledgeRetryWorker {

    private final UserEntitlementRepository entitlementRepository;
    private final EntitlementSyncService entitlementSyncService;
    private final PurchaseTokenCrypto purchaseTokenCrypto;

    @Value("${app.entitlement.google-play-ack-retry.stale-after:PT10M}")
    private Duration staleAfter;

    @Value("${app.entitlement.google-play-ack-retry.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.entitlement.google-play-ack-retry.fixed-delay:PT10M}")
    public void retryPendingAcknowledgements() {
        if (!purchaseTokenCrypto.enabled()) {
            log.warn("google_play_ack_retry_skipped token_crypto_disabled=true");
            return;
        }

        Instant now = Instant.now();
        Instant updatedBefore = now.minus(staleAfter);

        var rows = entitlementRepository.findAckPendingGooglePlayEntitlements(
                updatedBefore,
                PageRequest.of(0, Math.max(1, batchSize))
        );

        for (var row : rows) {
            try {
                entitlementSyncService.retryAcknowledgeForStoredToken(row.getId(), now);
            } catch (Exception ex) {
                log.warn(
                        "google_play_ack_retry_failed entitlementId={} userId={} error={}",
                        row.getId(),
                        row.getUserId(),
                        ex.toString()
                );
            }
        }

        if (!rows.isEmpty()) {
            log.info("google_play_ack_retry_done count={}", rows.size());
        }
    }
}
