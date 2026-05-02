package com.calai.backend.referral.service;

import com.calai.backend.entitlement.service.GooglePlayVerifierProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlayVoidedPurchaseChecker implements VoidedPurchaseChecker {

    private static final String BASE_URL =
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

    private static final long DEFAULT_LOOKBACK_SECONDS = 8L * 24L * 3600L;

    private final GooglePlayVerifierProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Final reward-time audit for Referral v1.3.
     *
     * RTDN is still the primary near-real-time signal, but before granting a reward we also check
     * Google Play voided purchases in the cooldown window. This protects against delayed/missed
     * RTDN for refund, revoke, chargeback, or other voided purchase events.
     */
    @Override
    public boolean isVoidedSubscriptionPurchase(
            String purchaseToken,
            Instant fromUtc,
            Instant toUtc
    ) {
        if (purchaseToken == null || purchaseToken.isBlank()) {
            return false;
        }

        if (props.isDevFakeTokensEnabled() && purchaseToken.startsWith("fake-dev-sub::")) {
            return false;
        }

        try {
            String accessToken = accessToken();
            long startMillis = normalizeStart(fromUtc).toEpochMilli();
            long endMillis = normalizeEnd(toUtc).toEpochMilli();
            String pageToken = null;

            do {
                String url = voidedPurchasesUrl(startMillis, endMillis, pageToken);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("GOOGLE_VOIDED_PURCHASES_HTTP_" + response.statusCode());
                }

                JsonNode body = objectMapper.readTree(response.body());
                if (containsPurchaseToken(body, purchaseToken)) {
                    log.info("google_voided_purchase_found purchaseTokenHashCandidatePresent=true");
                    return true;
                }

                pageToken = body.path("tokenPagination").path("nextPageToken").asText(null);
                if (pageToken != null && pageToken.isBlank()) {
                    pageToken = null;
                }
            } while (pageToken != null);

            return false;
        } catch (Exception ex) {
            throw new IllegalStateException("GOOGLE_VOIDED_PURCHASE_CHECK_FAILED", ex);
        }
    }

    private boolean containsPurchaseToken(JsonNode body, String purchaseToken) {
        JsonNode voidedPurchases = body.path("voidedPurchases");
        if (!voidedPurchases.isArray()) {
            return false;
        }

        for (JsonNode item : voidedPurchases) {
            String token = item.path("purchaseToken").asText(null);
            if (purchaseToken.equals(token)) {
                return true;
            }
        }

        return false;
    }

    private String voidedPurchasesUrl(long startMillis, long endMillis, String pageToken) {
        StringBuilder url = new StringBuilder()
                .append(BASE_URL)
                .append("/")
                .append(path(props.getPackageName()))
                .append("/purchases/voidedpurchases")
                .append("?startTime=").append(startMillis)
                .append("&endTime=").append(endMillis)
                .append("&type=1")
                .append("&maxResults=1000");

        if (pageToken != null && !pageToken.isBlank()) {
            url.append("&token=").append(path(pageToken));
        }

        return url.toString();
    }

    private Instant normalizeStart(Instant fromUtc) {
        Instant fallback = Instant.now().minusSeconds(DEFAULT_LOOKBACK_SECONDS);
        return (fromUtc == null ? fallback : fromUtc).minusSeconds(60L);
    }

    private Instant normalizeEnd(Instant toUtc) {
        return (toUtc == null ? Instant.now() : toUtc).plusSeconds(60L);
    }

    private String accessToken() throws Exception {
        GoogleCredentials credentials;

        if (props.getServiceAccountJsonPath() != null
                && !props.getServiceAccountJsonPath().isBlank()) {
            try (FileInputStream in = new FileInputStream(props.getServiceAccountJsonPath())) {
                credentials = GoogleCredentials.fromStream(in);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault();
        }

        credentials = credentials.createScoped(List.of(AndroidPublisherScopes.ANDROIDPUBLISHER));
        credentials.refreshIfExpired();

        return credentials.getAccessToken().getTokenValue();
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
