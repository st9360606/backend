package com.calai.backend.entitlement.service;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlaySubscriptionVerifier implements SubscriptionVerifier {

    private final AndroidPublisher publisher;
    private final GooglePlayVerifierProperties props;

    @Override
    public VerifiedSubscription verify(String purchaseToken) throws Exception {
        SubscriptionPurchaseV2 v2 = publisher.purchases()
                .subscriptionsv2()
                .get(props.getPackageName(), purchaseToken)
                .execute();

        List<SubscriptionPurchaseLineItem> items = v2.getLineItems();
        if (items == null || items.isEmpty()) return new VerifiedSubscription(false, null, null);

        SubscriptionPurchaseLineItem best = items.stream()
                .filter(it -> it.getExpiryTime() != null && it.getProductId() != null)
                .max(Comparator.comparing(it -> Instant.parse(it.getExpiryTime())))
                .orElse(null);

        if (best == null) return new VerifiedSubscription(false, null, null);

        Instant expiry = Instant.parse(best.getExpiryTime());
        boolean active = expiry.isAfter(Instant.now());

        return new VerifiedSubscription(active, best.getProductId(), expiry);
    }
}
