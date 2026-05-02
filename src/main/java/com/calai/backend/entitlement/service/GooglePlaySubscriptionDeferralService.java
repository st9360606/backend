package com.calai.backend.entitlement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlaySubscriptionDeferralService {

    private static final String BASE_URL =
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

    private final GooglePlayVerifierProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public record DeferralResult(
            Instant newExpiryUtc,
            String requestJson,
            String rawResponseJson,
            int httpStatus
    ) {}

    @Getter
    public static class DeferralException extends RuntimeException {
        private final String errorCode;
        private final boolean retryable;
        private final Integer httpStatus;
        private final String requestJson;
        private final String responseJson;

        public DeferralException(
                String errorCode,
                boolean retryable,
                Integer httpStatus,
                String requestJson,
                String responseJson,
                Throwable cause
        ) {
            super(errorCode, cause);
            this.errorCode = errorCode;
            this.retryable = retryable;
            this.httpStatus = httpStatus;
            this.requestJson = requestJson;
            this.responseJson = responseJson;
        }
    }

    public DeferralResult deferBy30Days(String purchaseToken) {
        return defer(purchaseToken, Duration.ofDays(30), false);
    }

    public DeferralResult defer(String purchaseToken, Duration duration, boolean validateOnly) {
        String requestJson = null;
        try {
            if (props.isDevFakeTokensEnabled()
                    && purchaseToken != null
                    && purchaseToken.startsWith("fake-dev-sub::")) {
                Instant fakeExpiry = Instant.now().plus(duration);
                requestJson = objectMapper.writeValueAsString(Map.of(
                        "devFake", true,
                        "deferDuration", duration.getSeconds() + "s",
                        "validateOnly", validateOnly
                ));
                String fakeJson = "{\"devFake\":true,\"itemExpiryTimeDetails\":[{\"expiryTime\":\""
                        + fakeExpiry + "\"}]}";
                return new DeferralResult(fakeExpiry, requestJson, fakeJson, 200);
            }

            String accessToken = accessToken();
            JsonNode current = getSubscription(purchaseToken, accessToken);
            String etag = current.path("etag").asText(null);

            if (etag == null || etag.isBlank()) {
                throw new DeferralException(
                        "GOOGLE_PLAY_ETAG_MISSING",
                        true,
                        null,
                        null,
                        current.toString(),
                        null
                );
            }

            requestJson = objectMapper.writeValueAsString(Map.of(
                    "deferralContext", Map.of(
                            "etag", etag,
                            "deferDuration", duration.getSeconds() + "s",
                            "validateOnly", validateOnly
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscriptionUrl(purchaseToken) + ":defer"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DeferralException(
                        "GOOGLE_PLAY_DEFER_HTTP_" + response.statusCode(),
                        isRetryableStatus(response.statusCode()),
                        response.statusCode(),
                        requestJson,
                        response.body(),
                        null
                );
            }

            JsonNode body = objectMapper.readTree(response.body());
            Instant maxExpiry = extractLatestExpiryUtc(body);

            if (maxExpiry == null) {
                throw new DeferralException(
                        "GOOGLE_PLAY_DEFER_RESPONSE_EXPIRY_MISSING",
                        true,
                        response.statusCode(),
                        requestJson,
                        response.body(),
                        null
                );
            }

            return new DeferralResult(maxExpiry, requestJson, response.body(), response.statusCode());

        } catch (DeferralException e) {
            throw e;
        } catch (Exception e) {
            throw new DeferralException(
                    "GOOGLE_PLAY_SUBSCRIPTION_DEFER_FAILED",
                    true,
                    null,
                    requestJson,
                    null,
                    e
            );
        }
    }

    private JsonNode getSubscription(String purchaseToken, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(subscriptionUrl(purchaseToken)))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DeferralException(
                    "GOOGLE_PLAY_GET_SUBSCRIPTION_HTTP_" + response.statusCode(),
                    isRetryableStatus(response.statusCode()),
                    response.statusCode(),
                    null,
                    response.body(),
                    null
            );
        }

        return objectMapper.readTree(response.body());
    }

    public Instant getCurrentExpiry(String purchaseToken) {
        String requestJson = null;

        try {
            if (props.isDevFakeTokensEnabled()
                    && purchaseToken != null
                    && purchaseToken.startsWith("fake-dev-sub::")) {
                /*
                 * Dev fake token does not have real Google Play server state.
                 * Return a future expiry so local referral/defer reconciliation can proceed in dev only.
                 */
                return Instant.now().plus(Duration.ofDays(30));
            }

            String accessToken = accessToken();
            JsonNode current = getSubscription(purchaseToken, accessToken);

            Instant expiry = extractLatestExpiryUtc(current);
            if (expiry == null) {
                throw new DeferralException(
                        "GOOGLE_PLAY_GET_SUBSCRIPTION_EXPIRY_MISSING",
                        true,
                        null,
                        requestJson,
                        current.toString(),
                        null
                );
            }

            return expiry;
        } catch (DeferralException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DeferralException(
                    "GOOGLE_SUBSCRIPTION_GET_FAILED",
                    true,
                    null,
                    requestJson,
                    null,
                    ex
            );
        }
    }

    private Instant extractLatestExpiryUtc(JsonNode body) {
        Instant maxExpiry = null;

        /*
         * subscriptionsv2.defer response:
         * {
         *   "itemExpiryTimeDetails": [
         *     { "expiryTime": "2026-05-31T00:00:00Z" }
         *   ]
         * }
         */
        JsonNode itemExpiryTimeDetails = body.path("itemExpiryTimeDetails");
        if (itemExpiryTimeDetails.isArray()) {
            for (JsonNode item : itemExpiryTimeDetails) {
                maxExpiry = max(maxExpiry, parseInstantOrNull(item.path("expiryTime").asText(null)));
            }
        }

        /*
         * subscriptionsv2.get response commonly exposes subscription line items:
         * {
         *   "lineItems": [
         *     { "expiryTime": "2026-05-31T00:00:00Z" }
         *   ]
         * }
         */
        JsonNode lineItems = body.path("lineItems");
        if (lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                maxExpiry = max(maxExpiry, parseInstantOrNull(item.path("expiryTime").asText(null)));
            }
        }

        return maxExpiry;
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant max(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }

        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }

        return current;
    }

    private String subscriptionUrl(String purchaseToken) {
        return BASE_URL + "/"
                + path(props.getPackageName())
                + "/purchases/subscriptionsv2/tokens/"
                + path(purchaseToken);
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

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 409 || status == 425 || status == 429 || status >= 500;
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
