package com.calai.backend.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalApiGuard {

    @Value("${app.internal.api-token:}")
    private String expectedToken;

    public void requireValidToken(String actualToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "INTERNAL_API_TOKEN_NOT_CONFIGURED"
            );
        }

        if (actualToken == null || actualToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "INTERNAL_API_TOKEN_REQUIRED"
            );
        }

        if (!constantTimeEquals(expectedToken, actualToken)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "INVALID_INTERNAL_API_TOKEN"
            );
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
