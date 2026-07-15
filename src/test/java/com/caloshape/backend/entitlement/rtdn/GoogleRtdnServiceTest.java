package com.caloshape.backend.entitlement.rtdn;

import com.caloshape.backend.entitlement.service.EntitlementSyncService;
import com.caloshape.backend.entitlement.service.GooglePlayVerifierProperties;
import com.caloshape.backend.referral.service.ReferralBillingBridgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GoogleRtdnServiceTest {

    private static final String PURCHASE_TOKEN = "purchase-token";
    private static final Instant EVENT_TIME = Instant.ofEpochMilli(1_750_000_000_000L);

    @Mock
    private ReferralBillingBridgeService referralBillingBridgeService;

    @Mock
    private EntitlementSyncService entitlementSyncService;

    private GoogleRtdnService service;

    @BeforeEach
    void setUp() {
        GooglePlayVerifierProperties googlePlayProperties = new GooglePlayVerifierProperties();
        googlePlayProperties.setPackageName("com.caloshape.app");
        service = new GoogleRtdnService(
                new ObjectMapper(),
                googlePlayProperties,
                referralBillingBridgeService,
                entitlementSyncService
        );
    }

    @Test
    void renewalReverifiesCurrentGoogleState() {
        service.handlePubSubMessage(subscriptionNotification(2), "message-renewed");

        verify(entitlementSyncService).syncPurchaseTokenFromRtdn(PURCHASE_TOKEN, EVENT_TIME);
        verify(entitlementSyncService, never()).closeByPurchaseTokenHash(anyString(), anyString(), any());
    }

    @Test
    void canceledNotificationReverifiesCurrentGoogleStateInsteadOfBlindlyClosing() {
        service.handlePubSubMessage(subscriptionNotification(3), "message-canceled");

        verify(entitlementSyncService).syncPurchaseTokenFromRtdn(PURCHASE_TOKEN, EVENT_TIME);
        verify(entitlementSyncService, never()).closeByPurchaseTokenHash(anyString(), anyString(), any());
    }

    @Test
    void revokedSubscriptionClosesAndReverifiesUsingCurrentGoogleState() {
        String tokenHash = EntitlementSyncService.sha256Hex(PURCHASE_TOKEN);

        service.handlePubSubMessage(subscriptionNotification(12), "message-revoked");

        verify(entitlementSyncService).closeByPurchaseTokenHash(tokenHash, "REVOKED", EVENT_TIME);
        verify(entitlementSyncService).syncPurchaseTokenFromRtdn(
                PURCHASE_TOKEN,
                EVENT_TIME,
                "REVOKED"
        );
        verify(referralBillingBridgeService).markRefundedOrRevoked(
                tokenHash,
                false,
                EVENT_TIME
        );
    }

    @Test
    void duplicateDeliveryReverifiesCurrentGoogleStateOnEachDelivery() {
        String data = subscriptionNotification(2);

        service.handlePubSubMessage(data, "message-duplicate");
        service.handlePubSubMessage(data, "message-duplicate");

        verify(entitlementSyncService, times(2))
                .syncPurchaseTokenFromRtdn(PURCHASE_TOKEN, EVENT_TIME);
    }

    @Test
    void testAndUnknownNotificationsAreAcknowledgedWithoutEntitlementMutation() {
        assertThatCode(() -> service.handlePubSubMessage(base64("""
                {"version":"1.0","packageName":"com.caloshape.app","eventTimeMillis":"1750000000000","testNotification":{"version":"1.0"}}
                """), "message-test")).doesNotThrowAnyException();
        assertThatCode(() -> service.handlePubSubMessage(base64("""
                {"version":"1.0","packageName":"com.caloshape.app","eventTimeMillis":"1750000000000","unknownNotification":{}}
                """), "message-unknown")).doesNotThrowAnyException();

        verify(entitlementSyncService, never()).syncPurchaseTokenFromRtdn(anyString(), any(Instant.class));
        verify(entitlementSyncService, never()).closeByPurchaseTokenHash(anyString(), anyString(), any());
        verify(referralBillingBridgeService, never()).markRefundedOrRevoked(
                anyString(),
                anyBoolean(),
                any(Instant.class)
        );
    }

    @Test
    void wrongPackageIsRejectedBeforeEntitlementMutation() {
        assertThatThrownBy(() -> service.handlePubSubMessage(base64("""
                {"version":"1.0","packageName":"another.app","eventTimeMillis":"1750000000000","testNotification":{"version":"1.0"}}
                """), "message-wrong-package"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RTDN_HANDLE_FAILED");

        verify(entitlementSyncService, never()).syncPurchaseTokenFromRtdn(anyString(), any(Instant.class));
    }

    @Test
    void malformedPayloadReturnsFailureSoPubSubCanRetry() {
        assertThatThrownBy(() -> service.handlePubSubMessage(base64("not-json"), "message-invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RTDN_HANDLE_FAILED");
    }

    private static String subscriptionNotification(int notificationType) {
        return base64("""
                {
                  "version":"1.0",
                  "packageName":"com.caloshape.app",
                  "eventTimeMillis":"1750000000000",
                  "subscriptionNotification":{
                    "version":"1.0",
                    "notificationType":%d,
                    "purchaseToken":"%s",
                    "subscriptionId":"caloshape_monthly"
                  }
                }
                """.formatted(notificationType, PURCHASE_TOKEN));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
