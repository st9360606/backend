package com.calai.backend.foodlog.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.crypto.aesgcm.*
 */
@ConfigurationProperties(prefix = "app.crypto.aesgcm")
public class AesGcmProperties {

    /**
     * Base64 encoded AES key (建議 32 bytes -> base64 44 chars)
     */
    private String keyB64;

    public String getKeyB64() {
        return keyB64;
    }

    public void setKeyB64(String keyB64) {
        this.keyB64 = keyB64;
    }
}
