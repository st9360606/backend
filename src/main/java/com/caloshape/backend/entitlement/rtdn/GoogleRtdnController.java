package com.caloshape.backend.entitlement.rtdn;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/google")
public class GoogleRtdnController {

    private final GoogleRtdnRequestAuthenticator requestAuthenticator;
    private final GoogleRtdnService googleRtdnService;

    @PostMapping("/rtdn")
    public Map<String, Object> receiveRtdn(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestBody PubSubPushRequest request
    ) {
        requestAuthenticator.requireAuthorized(authorization, internalToken);

        if (request == null || request.message() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_PUBSUB_MESSAGE"
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
