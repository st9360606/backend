package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
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
public class GooglePlayEntitlementReverifyWorker {

    private final UserEntitlementRepository entitlementRepository;
    private final EntitlementSyncService entitlementSyncService;
    private final PurchaseTokenCrypto purchaseTokenCrypto;

    @Value("${app.entitlement.google-play-reverify.stale-after:PT6H}")
    private Duration staleAfter;

    @Value("${app.entitlement.google-play-reverify.batch-size:50}")
    private int batchSize;

    /**
     * Periodically re-verifies active Google Play entitlements.
     * This closes the RTDN-missed gap for ON_HOLD / EXPIRED / REVOKED / GRACE transitions.
     */
    @Scheduled(fixedDelayString = "${app.entitlement.google-play-reverify.fixed-delay:PT30M}")
    public void reverifyActiveGooglePlayEntitlements() {
        if (!purchaseTokenCrypto.enabled()) {
            log.warn("google_play_reverify_skipped token_crypto_disabled=true");
            return;
        }

        Instant now = Instant.now();
        Instant verifiedBefore = now.minus(staleAfter);

        var due = entitlementRepository.findActiveGooglePlayDueForReverify(
                verifiedBefore,
                PageRequest.of(0, Math.max(1, batchSize))
        );

        for (UserEntitlementEntity e : due) {
            String purchaseToken = purchaseTokenCrypto.decryptOrNull(e.getPurchaseTokenCiphertext());
            if (purchaseToken == null || purchaseToken.isBlank()) {
                log.warn(
                        "google_play_reverify_token_decrypt_failed entitlementId={} userId={} tokenHash={}",
                        e.getId(),
                        e.getUserId(),
                        e.getPurchaseTokenHash()
                );
                continue;
            }

            try {
                entitlementSyncService.syncKnownPurchaseTokenFromRtdn(
                        e.getUserId(),
                        purchaseToken,
                        now
                );
            } catch (Exception ex) {
                log.warn(
                        "google_play_reverify_failed entitlementId={} userId={} tokenHash={} error={}",
                        e.getId(),
                        e.getUserId(),
                        e.getPurchaseTokenHash(),
                        ex.toString()
                );
            }
        }

        if (!due.isEmpty()) {
            log.info("google_play_reverify_done count={} verifiedBefore={}", due.size(), verifiedBefore);
        }
    }
}
