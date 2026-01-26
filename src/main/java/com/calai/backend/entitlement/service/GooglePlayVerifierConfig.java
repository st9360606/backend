package com.calai.backend.entitlement.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.io.FileInputStream;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GooglePlayVerifierProperties.class)
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlayVerifierConfig {

    @Bean
    public AndroidPublisher androidPublisher(GooglePlayVerifierProperties props) throws Exception {
        GoogleCredentials creds;

        if (props.getServiceAccountJsonPath() != null && !props.getServiceAccountJsonPath().isBlank()) {
            try (FileInputStream in = new FileInputStream(props.getServiceAccountJsonPath())) {
                creds = GoogleCredentials.fromStream(in);
            }
        } else {
            // ✅ 只有 enabled=true 才會進來；test 不會建立這個 bean
            creds = GoogleCredentials.getApplicationDefault();
        }

        creds = creds.createScoped(List.of(AndroidPublisherScopes.ANDROIDPUBLISHER));
        HttpRequestInitializer init = new HttpCredentialsAdapter(creds);

        return new AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                init
        ).setApplicationName("BiteCalBackend").build();
    }
}
