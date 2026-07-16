package com.caloshape.backend.accountdelete.service;

import com.caloshape.backend.common.crypto.HmacSha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountDeletionPseudonymizer {

    private final String secret;

    public AccountDeletionPseudonymizer(
            @Value("${app.account-deletion.pseudonym-key}") String secret
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("Account deletion pseudonym key must be at least 32 characters");
        }
        this.secret = secret;
    }

    public String emailHash(String normalizedEmail) {
        return HmacSha256.hex(secret, "email:" + normalizedEmail);
    }

    public long userId(Long userId) {
        String digest = HmacSha256.hex(secret, "user-id:" + userId);
        long bits = Long.parseUnsignedLong(digest.substring(0, 16), 16);
        long positive = Long.remainderUnsigned(bits, Long.MAX_VALUE - 1) + 1;
        return -positive;
    }
}
