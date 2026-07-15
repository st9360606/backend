package com.caloshape.backend.entitlement.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GooglePlayVerifierProperties.class)
@ConditionalOnProperty(prefix = "app.google.play", name = "enabled", havingValue = "true")
public class GooglePlayVerifierConfig {

    @Bean
    public AndroidPublisher androidPublisher(GooglePlayCredentialsLoader credentialsLoader) throws Exception {
        GoogleCredentials credentials = credentialsLoader.loadScoped();
        HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);

        return new AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                initializer
        ).setApplicationName("CaloShapeBackend").build();
    }
}
