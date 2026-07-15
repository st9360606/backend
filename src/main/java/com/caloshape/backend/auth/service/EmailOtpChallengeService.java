package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.email.EmailLoginCode;
import com.caloshape.backend.auth.repo.EmailLoginCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class EmailOtpChallengeService {

    public enum Status {
        VERIFIED,
        INVALID,
        INVALID_OR_EXPIRED,
        TOO_MANY_ATTEMPTS
    }

    public record Result(Status status, int retryAfterSec) {
    }

    private final EmailLoginCodeRepository codes;
    private final int maxAttempts;

    public EmailOtpChallengeService(
            EmailLoginCodeRepository codes,
            @Value("${app.email.max-attempts:5}") int maxAttempts
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.email.max-attempts must be positive");
        }
        this.codes = codes;
        this.maxAttempts = maxAttempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result verify(String email, String purpose, String submittedCode, Instant now) {
        EmailLoginCode challenge = codes
                .findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                        email,
                        purpose,
                        now
                )
                .orElse(null);

        if (challenge == null) {
            return new Result(Status.INVALID_OR_EXPIRED, 0);
        }

        int attemptsBefore = challenge.getAttemptCnt() == null ? 0 : challenge.getAttemptCnt();
        if (attemptsBefore >= maxAttempts) {
            challenge.setConsumedAt(now);
            codes.save(challenge);
            return new Result(Status.TOO_MANY_ATTEMPTS, retryAfterSec(challenge, now));
        }

        int attemptsAfter = attemptsBefore + 1;
        challenge.setAttemptCnt(attemptsAfter);

        if (EmailOtpCodeSupport.matchesHash(challenge.getCodeHash(), submittedCode)) {
            challenge.setConsumedAt(now);
            codes.save(challenge);
            return new Result(Status.VERIFIED, 0);
        }

        if (attemptsAfter >= maxAttempts) {
            challenge.setConsumedAt(now);
            codes.save(challenge);
            return new Result(Status.TOO_MANY_ATTEMPTS, retryAfterSec(challenge, now));
        }

        codes.save(challenge);
        return new Result(Status.INVALID, 0);
    }

    private static int retryAfterSec(EmailLoginCode challenge, Instant now) {
        long seconds = Math.max(1L, Duration.between(now, challenge.getExpiresAt()).getSeconds());
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
