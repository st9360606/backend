package com.caloshape.backend.entitlement.service;

import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlayCredentialsLoader {

    private static final int MAX_JSON_BYTES = 64 * 1024;

    private final GooglePlayVerifierProperties properties;

    public GoogleCredentials loadScoped() throws IOException {
        return load().createScoped(List.of(AndroidPublisherScopes.ANDROIDPUBLISHER));
    }

    GoogleCredentials load() throws IOException {
        String path = trimToNull(properties.getServiceAccountJsonPath());
        String encodedJson = trimToNull(properties.getServiceAccountJsonBase64());

        if (path != null && encodedJson != null) {
            throw new IllegalStateException("Multiple Google Play credential sources are configured");
        }
        if (encodedJson != null) {
            return loadBase64(encodedJson);
        }
        if (path != null) {
            try (InputStream input = Files.newInputStream(Path.of(path))) {
                return GoogleCredentials.fromStream(input);
            }
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private static GoogleCredentials loadBase64(String encodedJson) throws IOException {
        byte[] jsonBytes;
        try {
            jsonBytes = Base64.getDecoder().decode(encodedJson);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Google Play credential Base64 is invalid", exception);
        }

        try {
            if (jsonBytes.length == 0 || jsonBytes.length > MAX_JSON_BYTES) {
                throw new IllegalStateException("Google Play credential JSON size is invalid");
            }
            try (InputStream input = new ByteArrayInputStream(jsonBytes)) {
                return GoogleCredentials.fromStream(input);
            }
        } finally {
            Arrays.fill(jsonBytes, (byte) 0);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
