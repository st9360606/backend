package com.calai.backend.entitlement.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Component
public class PurchaseTokenCrypto {

    private static final String ALGO = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    public PurchaseTokenCrypto(
            @Value("${app.google.play.purchase-token-encryption-key:}") String base64Key
    ) {
        this.keySpec = buildKey(base64Key);
    }

    public boolean enabled() {
        return keySpec != null;
    }

    public String encryptOrNull(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || keySpec == null) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            log.warn("purchase_token_encrypt_failed error={}", ex.toString());
            return null;
        }
    }

    public String decryptOrNull(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank() || keySpec == null) {
            return null;
        }

        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            if (raw.length <= IV_BYTES) {
                return null;
            }

            byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(raw, IV_BYTES, raw.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));

            return new String(
                    cipher.doFinal(encrypted),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (Exception ex) {
            log.warn("purchase_token_decrypt_failed error={}", ex.toString());
            return null;
        }
    }

    private static SecretKeySpec buildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }

        try {
            byte[] key = Base64.getDecoder().decode(base64Key.trim());
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("AES key must be 16, 24, or 32 bytes");
            }
            return new SecretKeySpec(key, ALGO);
        } catch (Exception ex) {
            log.warn("purchase_token_crypto_disabled_invalid_key error={}", ex.toString());
            return null;
        }
    }
}
