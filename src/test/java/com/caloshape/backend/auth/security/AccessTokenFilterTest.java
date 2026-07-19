package com.caloshape.backend.auth.security;

import com.caloshape.backend.auth.entity.AuthToken;
import com.caloshape.backend.auth.repo.AuthTokenRepo;
import com.caloshape.backend.auth.service.AuthTokenHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenFilterTest {

    @Mock
    private AuthTokenRepo tokenRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesUsingTheHashPersistedForTheRawAccessToken() throws Exception {
        String rawToken = "access-token-for-filter-test";
        AuthToken storedAccessToken = activeToken(AuthToken.TokenType.ACCESS);
        when(tokenRepository.findByToken(AuthTokenHash.sha256(rawToken)))
                .thenReturn(Optional.of(storedAccessToken));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/membership/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AccessTokenFilter(tokenRepository).doFilter(
                request,
                response,
                new MockFilterChain()
        );

        ArgumentCaptor<String> lookup = ArgumentCaptor.forClass(String.class);
        verify(tokenRepository).findByToken(lookup.capture());
        assertThat(lookup.getValue())
                .isEqualTo(AuthTokenHash.sha256(rawToken))
                .isNotEqualTo(rawToken);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(42L);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsARefreshTokenOnAnAuthenticatedApiRoute() throws Exception {
        String rawRefreshToken = "refresh-token-for-filter-test";
        AuthToken storedRefreshToken = activeToken(AuthToken.TokenType.REFRESH);
        when(tokenRepository.findByToken(AuthTokenHash.sha256(rawRefreshToken)))
                .thenReturn(Optional.of(storedRefreshToken));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/membership/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawRefreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AccessTokenFilter(tokenRepository).doFilter(
                request,
                response,
                new MockFilterChain()
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static AuthToken activeToken(AuthToken.TokenType type) {
        AuthToken token = new AuthToken();
        token.setType(type);
        token.setExpiresAt(Instant.now().plusSeconds(60));
        token.setUserId(42L);
        token.setRevoked(false);
        return token;
    }
}
