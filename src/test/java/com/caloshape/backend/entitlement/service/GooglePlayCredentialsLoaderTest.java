package com.caloshape.backend.entitlement.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GooglePlayCredentialsLoaderTest {

    private static final String TEST_AUTHORIZED_USER_JSON = """
            {
              "type": "authorized_user",
              "client_id": "test-client",
              "client_secret": "test-secret",
              "refresh_token": "test-refresh-token"
            }
            """;

    @TempDir
    Path tempDirectory;

    @Test
    void loadsBase64JsonWithoutWritingCredentialFile() throws Exception {
        GooglePlayVerifierProperties properties = new GooglePlayVerifierProperties();
        properties.setServiceAccountJsonBase64(Base64.getEncoder().encodeToString(
                TEST_AUTHORIZED_USER_JSON.getBytes(StandardCharsets.UTF_8)
        ));

        GoogleCredentials credentials = new GooglePlayCredentialsLoader(properties).load();

        assertThat(credentials).isInstanceOf(UserCredentials.class);
    }

    @Test
    void stillSupportsLocalCredentialPath() throws Exception {
        Path credentialFile = tempDirectory.resolve("credential.json");
        Files.writeString(credentialFile, TEST_AUTHORIZED_USER_JSON, StandardCharsets.UTF_8);

        GooglePlayVerifierProperties properties = new GooglePlayVerifierProperties();
        properties.setServiceAccountJsonPath(credentialFile.toString());

        GoogleCredentials credentials = new GooglePlayCredentialsLoader(properties).load();

        assertThat(credentials).isInstanceOf(UserCredentials.class);
    }

    @Test
    void rejectsInvalidBase64WithSanitizedMessage() {
        GooglePlayVerifierProperties properties = new GooglePlayVerifierProperties();
        properties.setServiceAccountJsonBase64("not-valid-base64!");

        assertThatThrownBy(() -> new GooglePlayCredentialsLoader(properties).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Google Play credential Base64 is invalid")
                .hasMessageNotContaining("not-valid-base64");
    }

    @Test
    void rejectsAmbiguousCredentialSources() {
        GooglePlayVerifierProperties properties = new GooglePlayVerifierProperties();
        properties.setServiceAccountJsonPath("C:/secret/play.json");
        properties.setServiceAccountJsonBase64("encoded-json");

        assertThatThrownBy(() -> new GooglePlayCredentialsLoader(properties).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Multiple Google Play credential sources are configured");
    }
}
