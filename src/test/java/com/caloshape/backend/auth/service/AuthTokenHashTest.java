package com.caloshape.backend.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenHashTest {

    @Test
    void sha256_returnsStableNonRawLookupValue() {
        String rawToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String hash = AuthTokenHash.sha256(rawToken);

        assertThat(hash)
                .hasSize(64)
                .isNotEqualTo(rawToken)
                .isEqualTo(AuthTokenHash.sha256(rawToken));
    }

    @Test
    void sha256_rejectsMissingTokenWithoutPersistingAPlaceholder() {
        assertThat(AuthTokenHash.sha256(null)).isNull();
        assertThat(AuthTokenHash.sha256(" ")).isNull();
    }
}
