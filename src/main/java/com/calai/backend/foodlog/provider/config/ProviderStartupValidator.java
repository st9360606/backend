package com.calai.backend.foodlog.provider.config;

import com.calai.backend.foodlog.task.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderStartupValidator {

    private final ProviderClient providerClient;

    @Value("${app.foodlog.provider:GEMINI}")
    private String configuredProvider;

    @PostConstruct
    public void validate() {
        String configured = normalize(configuredProvider);
        String actual = normalize(providerClient.providerCode());

        log.info("foodlog provider startup check: configured={}, actualBean={}", configured, actual);

        if (!configured.equals(actual)) {
            throw new IllegalStateException(
                    "FOODLOG_PROVIDER_MISMATCH: configured=" + configured + ", actualBean=" + actual
            );
        }
    }

    private static String normalize(String s) {
        if (s == null || s.isBlank()) return "";
        return s.trim().toUpperCase(Locale.ROOT);
    }
}