package com.caloshape.backend.entitlement.rtdn;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GoogleRtdnControllerTest {

    private final GoogleRtdnRequestAuthenticator authenticator =
            mock(GoogleRtdnRequestAuthenticator.class);
    private final GoogleRtdnService service = mock(GoogleRtdnService.class);
    private final GoogleRtdnController controller = new GoogleRtdnController(authenticator, service);

    @Test
    void authenticatedPushForwardsPubSubDataAndMessageId() {
        GoogleRtdnController.PubSubPushRequest request = new GoogleRtdnController.PubSubPushRequest(
                new GoogleRtdnController.PubSubMessage(Map.of(), "base64-data", "message-1"),
                "projects/example/subscriptions/rtdn"
        );

        Map<String, Object> response = controller.receiveRtdn(
                "Bearer signed-token",
                null,
                request
        );

        verify(authenticator).requireAuthorized("Bearer signed-token", null);
        verify(service).handlePubSubMessage("base64-data", "message-1");
        assertThat(response).containsEntry("ok", true);
    }

    @Test
    void emptyEnvelopeReturnsBadRequestAfterAuthenticationWithoutCallingService() {
        GoogleRtdnController.PubSubPushRequest request =
                new GoogleRtdnController.PubSubPushRequest(null, "subscription");

        assertThatThrownBy(() -> controller.receiveRtdn(null, "legacy-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(
                        ((ResponseStatusException) ex).getStatusCode().value()
                ).isEqualTo(400));

        verify(authenticator).requireAuthorized(null, "legacy-token");
        verify(service, never()).handlePubSubMessage(null, null);
    }
}
