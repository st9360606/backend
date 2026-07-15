package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.email.EmailLoginCode;
import com.caloshape.backend.auth.repo.EmailLoginCodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailOtpChallengeServiceTest {

    private static final String EMAIL = "person@example.com";
    private static final String PURPOSE = "LOGIN";
    private static final Instant NOW = Instant.parse("2026-07-14T04:00:00Z");

    @Test
    void fifthInvalidAttemptLocksChallengeAndPersistsAttemptCount() {
        EmailLoginCodeRepository repository = mock(EmailLoginCodeRepository.class);
        EmailLoginCode challenge = challenge("654321");
        when(repository.findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                EMAIL,
                PURPOSE,
                NOW
        )).thenReturn(Optional.of(challenge));
        when(repository.save(any(EmailLoginCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailOtpChallengeService service = new EmailOtpChallengeService(repository, 5);

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(service.verify(EMAIL, PURPOSE, "000000", NOW).status())
                    .isEqualTo(EmailOtpChallengeService.Status.INVALID);
        }

        EmailOtpChallengeService.Result fifth = service.verify(EMAIL, PURPOSE, "000000", NOW);

        assertThat(fifth.status()).isEqualTo(EmailOtpChallengeService.Status.TOO_MANY_ATTEMPTS);
        assertThat(fifth.retryAfterSec()).isEqualTo(600);
        assertThat(challenge.getAttemptCnt()).isEqualTo(5);
        assertThat(challenge.getConsumedAt()).isEqualTo(NOW);
    }

    @Test
    void correctCodeConsumesChallengeAfterPriorInvalidAttempt() {
        EmailLoginCodeRepository repository = mock(EmailLoginCodeRepository.class);
        EmailLoginCode challenge = challenge("654321");
        when(repository.findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                EMAIL,
                PURPOSE,
                NOW
        )).thenReturn(Optional.of(challenge));
        when(repository.save(any(EmailLoginCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailOtpChallengeService service = new EmailOtpChallengeService(repository, 5);

        assertThat(service.verify(EMAIL, PURPOSE, "000000", NOW).status())
                .isEqualTo(EmailOtpChallengeService.Status.INVALID);
        assertThat(service.verify(EMAIL, PURPOSE, "654321", NOW).status())
                .isEqualTo(EmailOtpChallengeService.Status.VERIFIED);
        assertThat(challenge.getAttemptCnt()).isEqualTo(2);
        assertThat(challenge.getConsumedAt()).isEqualTo(NOW);
    }

    @Test
    void missingOrExpiredChallengeReturnsGenericFailure() {
        EmailLoginCodeRepository repository = mock(EmailLoginCodeRepository.class);
        when(repository.findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                EMAIL,
                PURPOSE,
                NOW
        )).thenReturn(Optional.empty());

        EmailOtpChallengeService service = new EmailOtpChallengeService(repository, 5);

        assertThat(service.verify(EMAIL, PURPOSE, "654321", NOW).status())
                .isEqualTo(EmailOtpChallengeService.Status.INVALID_OR_EXPIRED);
    }

    @Test
    void verificationUsesRequiresNewTransactionSoFailedAttemptsCanCommit() throws Exception {
        Method method = EmailOtpChallengeService.class.getMethod(
                "verify",
                String.class,
                String.class,
                String.class,
                Instant.class
        );

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static EmailLoginCode challenge(String code) {
        EmailLoginCode challenge = new EmailLoginCode();
        challenge.setId(1L);
        challenge.setEmail(EMAIL);
        challenge.setPurpose(PURPOSE);
        challenge.setCodeHash(EmailOtpCodeSupport.sha256(code));
        challenge.setAttemptCnt(0);
        challenge.setCreatedAt(NOW.minusSeconds(60));
        challenge.setExpiresAt(NOW.plusSeconds(600));
        return challenge;
    }
}
