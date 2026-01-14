package com.calai.backend.foodlog;

import com.calai.backend.foodlog.crypto.AesGcmCrypto;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCryptoTest {

    @Test
    void encrypt_then_decrypt_roundtrip() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        String keyB64 = Base64.getEncoder().encodeToString(key);

        AesGcmCrypto c = new AesGcmCrypto(keyB64);

        String plain = "logmeal-token-abc123";
        String enc = c.encryptToBase64(plain);
        assertNotNull(enc);
        assertNotEquals(plain, enc);

        String dec = c.decryptFromBase64(enc);
        assertEquals(plain, dec);
    }
}
