package com.calai.backend.entitlement.rtdn;

import com.calai.backend.internal.InternalApiGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/google")
public class GoogleRtdnController {

    private final InternalApiGuard internalApiGuard;
    private final GoogleRtdnService googleRtdnService;

    @PostMapping("/rtdn")
    public Map<String, Object> receiveRtdn(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestBody PubSubPushRequest request
    ) {
        internalApiGuard.requireValidToken(internalToken);

        if (request == null || request.message() == null) {
            return Map.of(
                    "ok", false,
                    "message", "EMPTY_PUBSUB_MESSAGE"
            );
        }

        googleRtdnService.handlePubSubMessage(
                request.message().data(),
                request.message().messageId()
        );

        return Map.of("ok", true);
    }

    public record PubSubPushRequest(
            PubSubMessage message,
            String subscription
    ) {}

    public record PubSubMessage(
            Map<String, String> attributes,
            String data,
            String messageId
    ) {}
}
