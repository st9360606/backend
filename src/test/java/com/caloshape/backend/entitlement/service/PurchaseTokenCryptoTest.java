package com.caloshape.backend.entitlement.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseTokenCryptoTest {

    @Test
    void validAes256KeyEncryptsAndDecryptsPurchaseToken() {
        PurchaseTokenCrypto crypto = new PurchaseTokenCrypto(aes256Key());

        String ciphertext = crypto.encryptOrNull("purchase-token-value");

        assertThat(crypto.enabled()).isTrue();
        assertThat(ciphertext)
                .isNotBlank()
                .doesNotContain("purchase-token-value");
        assertThat(crypto.decryptOrNull(ciphertext)).isEqualTo("purchase-token-value");
    }

    @Test
    void repeatedEncryptionUsesDifferentIv() {
        PurchaseTokenCrypto crypto = new PurchaseTokenCrypto(aes256Key());

        String first = crypto.encryptOrNull("same-token");
        String second = crypto.encryptOrNull("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decryptOrNull(first)).isEqualTo("same-token");
        assertThat(crypto.decryptOrNull(second)).isEqualTo("same-token");
    }

    @Test
    void missingOrInvalidKeyDisablesCrypto() {
        PurchaseTokenCrypto missing = new PurchaseTokenCrypto(" ");
        PurchaseTokenCrypto invalid = new PurchaseTokenCrypto("not-base64");
        PurchaseTokenCrypto aes128 = new PurchaseTokenCrypto(
                Base64.getEncoder().encodeToString(new byte[16])
        );

        assertThat(missing.enabled()).isFalse();
        assertThat(invalid.enabled()).isFalse();
        assertThat(aes128.enabled()).isFalse();
        assertThat(missing.encryptOrNull("token")).isNull();
        assertThat(invalid.encryptOrNull("token")).isNull();
        assertThat(aes128.encryptOrNull("token")).isNull();
    }

    private static String aes256Key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
