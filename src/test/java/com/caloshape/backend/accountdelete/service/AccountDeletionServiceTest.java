package com.caloshape.backend.accountdelete.service;

import com.caloshape.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.caloshape.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.caloshape.backend.auth.entity.AuthProvider;
import com.caloshape.backend.auth.repo.AuthTokenRepo;
import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDeletionServiceTest {

    private final AccountDeletionRequestRepository requestRepository = mock(AccountDeletionRequestRepository.class);
    private final UserRepo userRepository = mock(UserRepo.class);
    private final AuthTokenRepo authTokenRepository = mock(AuthTokenRepo.class);
    private final UserEntitlementRepository entitlementRepository = mock(UserEntitlementRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccountDeletionPseudonymizer pseudonymizer =
            new AccountDeletionPseudonymizer("test-account-deletion-pseudonym-key-32-chars");
    private final AccountDeletionService service = new AccountDeletionService(
            requestRepository,
            userRepository,
            authTokenRepository,
            entitlementRepository,
            jdbc,
            pseudonymizer
    );

    @Test
    void requestDeletion_requiresExplicitWarningForAnActiveGooglePlaySubscription() {
        UserEntitlementEntity subscription = activeGooglePlayEntitlement();
        when(entitlementRepository.findActiveBestFirst(eq(7L), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(subscription));

        assertThatThrownBy(() -> service.requestDeletion(7L, false, false))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requestDeletion_withoutSubscriptionRemovesIdentifiersAndRevokesTokens() {
        User user = new User();
        user.setId(8L);
        user.setProvider(AuthProvider.EMAIL);
        user.setEmail("person@example.com");
        user.setName("Person");
        user.setGoogleSub("google-subject");

        when(entitlementRepository.findActiveBestFirst(eq(8L), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(requestRepository.findByUserIdForUpdate(8L)).thenReturn(Optional.empty());
        when(userRepository.findByIdForUpdate(8L)).thenReturn(user);
        when(requestRepository.save(any(AccountDeletionRequestEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountDeletionRequestEntity request = service.requestDeletion(8L, false, false);

        assertThat(request.getReqStatus()).isEqualTo("REQUESTED");
        assertThat(user.getStatus()).isEqualTo("DELETING");
        assertThat(user.getEmail()).isNull();
        assertThat(user.getGoogleSub()).isNull();
        assertThat(user.getName()).isNull();
        assertThat(user.getDeletedEmailHash()).isEqualTo(pseudonymizer.emailHash("person@example.com"));
        verify(jdbc).update("DELETE FROM email_login_codes WHERE email = ?", "person@example.com");
        verify(authTokenRepository).revokeAllByUserId(eq(8L), any(Instant.class));
        verify(userRepository).save(user);
    }

    private UserEntitlementEntity activeGooglePlayEntitlement() {
        UserEntitlementEntity entitlement = new UserEntitlementEntity();
        entitlement.setSource("GOOGLE_PLAY");
        entitlement.setEntitlementType("MONTHLY");
        entitlement.setStatus("ACTIVE");
        entitlement.setValidToUtc(Instant.now().plusSeconds(3_600));
        return entitlement;
    }
}
