package com.calai.backend.foodlog.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AesGcmProperties.class)
public class CryptoConfig {

    /**
     * 只有在 LOGMEAL provider 才需要 AES-GCM（用來加解密 per-user api token）
     */
    @Bean
    @ConditionalOnProperty(name = "app.foodlog.provider", havingValue = "LOGMEAL")
    public AesGcmCrypto aesGcmCrypto(AesGcmProperties props) {
        String keyB64 = props.getKeyB64();
        if (keyB64 == null || keyB64.isBlank()) {
            // ✅ 這個錯誤會比 PlaceholderResolutionException 友善很多
            throw new IllegalStateException(
                    "Missing app.crypto.aesgcm.key-b64. " +
                    "Set env APP_CRYPTO_AESGCM_KEY_B64 (base64-encoded AES key)."
            );
        }
        return new AesGcmCrypto(keyB64);
    }
}
