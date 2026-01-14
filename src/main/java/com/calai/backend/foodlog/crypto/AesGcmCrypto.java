package com.calai.backend.foodlog.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyBase64 32 bytes(建議) 的 base64；例如用 KMS/SecretManager 發 32 bytes key
     */
    public AesGcmCrypto(String keyBase64) {
        byte[] raw = Base64.getDecoder().decode(keyBase64);
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encryptToBase64(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("CRYPTO_ENCRYPT_FAILED", e);
        }
    }

    public String decryptFromBase64(String cipherB64) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherB64);
            if (all.length <= IV_BYTES) throw new IllegalArgumentException("CRYPTO_BAD_CIPHER");

            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            byte[] pt = c.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("CRYPTO_DECRYPT_FAILED", e);
        }
    }
}
