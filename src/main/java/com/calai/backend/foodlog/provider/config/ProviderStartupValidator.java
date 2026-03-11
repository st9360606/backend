package com.calai.backend.foodlog.provider.config;

import com.calai.backend.foodlog.task.ProviderClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(ProviderClient.class)
public class ProviderStartupValidator {

    private final ProviderClient providerClient;

    @Value("${app.foodlog.provider:}")
    private String configuredProvider;

    @PostConstruct
    public void validate() {
        String actual = normalize(providerClient.providerCode());

        // local/test stub 常見情境：直接略過
        if ("STUB".equals(actual)) {
            log.info("foodlog provider startup check skipped for stub provider");
            return;
        }

        String configured = normalize(configuredProvider);

        // 沒配置就不檢查，避免無意義 fail-fast
        if (configured.isBlank()) {
            log.info("foodlog provider startup check skipped because app.foodlog.provider is blank");
            return;
        }

        log.info("foodlog provider startup check: configured={}, actualBean={}", configured, actual);

        if (!configured.equals(actual)) {
            throw new IllegalStateException(
                    "FOODLOG_PROVIDER_MISMATCH: configured=" + configured + ", actualBean=" + actual
            );
        }
    }

    private static String normalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.trim().toUpperCase(Locale.ROOT);
    }
}
