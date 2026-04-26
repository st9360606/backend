package com.calai.backend.entitlement.service;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.SubscriptionPurchasesAcknowledgeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlayAcknowledgeService implements PurchaseAcknowledger {

    private final AndroidPublisher publisher;
    private final GooglePlayVerifierProperties props;

    @Override
    public boolean acknowledgeWithRetry(
            String productId,
            String purchaseToken,
            String acknowledgementState
    ) {
        if (productId == null || productId.isBlank()) return false;
        if (purchaseToken == null || purchaseToken.isBlank()) return false;

        if ("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(acknowledgementState)) {
            return true;
        }

        int maxAttempts = 3;
        long delayMs = 500L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                publisher.purchases()
                        .subscriptions()
                        .acknowledge(
                                props.getPackageName(),
                                productId,
                                purchaseToken,
                                new SubscriptionPurchasesAcknowledgeRequest()
                        )
                        .execute();

                log.info(
                        "google_play_ack_success productId={} attempt={}",
                        productId,
                        attempt
                );
                return true;
            } catch (Exception ex) {
                log.warn(
                        "google_play_ack_failed productId={} attempt={} error={}",
                        productId,
                        attempt,
                        ex.toString()
                );

                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    delayMs *= 2;
                }
            }
        }

        return false;
    }
}
