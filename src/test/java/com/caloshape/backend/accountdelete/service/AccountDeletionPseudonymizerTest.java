package com.caloshape.backend.accountdelete.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDeletionPseudonymizerTest {

    private final AccountDeletionPseudonymizer pseudonymizer =
            new AccountDeletionPseudonymizer("test-account-deletion-pseudonym-key-32-chars");

    @Test
    void emailHash_isKeyedStableAndDoesNotExposePlainSha256() {
        String hash = pseudonymizer.emailHash("person@example.com");

        assertThat(hash)
                .hasSize(64)
                .isEqualTo(pseudonymizer.emailHash("person@example.com"))
                .isNotEqualTo("b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514");
    }

    @Test
    void userId_isStableNegativeAndSeparatedFromLiveIds() {
        long pseudonymousId = pseudonymizer.userId(123L);

        assertThat(pseudonymousId).isNegative();
        assertThat(pseudonymousId).isEqualTo(pseudonymizer.userId(123L));
        assertThat(pseudonymousId).isNotEqualTo(pseudonymizer.userId(124L));
    }
}
