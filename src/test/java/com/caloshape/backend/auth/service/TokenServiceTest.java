package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.entity.AuthToken;
import com.caloshape.backend.auth.repo.AuthTokenRepo;
import com.caloshape.backend.users.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private AuthTokenRepo repository;

    @Test
    void issue_returnsRawTokensButPersistsOnlyHashes() {
        TokenService service = new TokenService(repository, 900, 2_592_000);
        User user = new User();

        TokenService.AuthPair pair = service.issue(user, "device", "127.0.0.1", "agent");

        ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuthToken::getToken)
                .containsExactly(
                        AuthTokenHash.sha256(pair.accessToken()),
                        AuthTokenHash.sha256(pair.refreshToken())
                )
                .doesNotContain(pair.accessToken(), pair.refreshToken());
    }

    @Test
    void rotateRefresh_looksUpWithHashUnderPessimisticLockAndRevokesOldToken() {
        TokenService service = new TokenService(repository, 900, 2_592_000);
        User user = new User();
        String rawRefresh = "old-refresh-token";
        AuthToken stored = new AuthToken();
        stored.setUser(user);
        stored.setType(AuthToken.TokenType.REFRESH);
        stored.setExpiresAt(Instant.now().plusSeconds(60));
        when(repository.findByTokenForUpdate(AuthTokenHash.sha256(rawRefresh)))
                .thenReturn(Optional.of(stored));

        TokenService.AuthPair replacement =
                service.rotateRefresh(rawRefresh, "device", "127.0.0.1", "agent");

        assertThat(stored.isRevoked()).isTrue();
        assertThat(stored.getReplacedBy()).isEqualTo(AuthTokenHash.sha256(replacement.refreshToken()));
    }
}
