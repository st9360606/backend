package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.dto.AuthResponse;
import com.caloshape.backend.auth.dto.StartRequest;
import com.caloshape.backend.auth.dto.StartResponse;
import com.caloshape.backend.auth.dto.VerifyRequest;
import com.caloshape.backend.auth.email.EmailLoginCode;
import com.caloshape.backend.auth.email.EmailLoginCodeRequestedEvent;
import com.caloshape.backend.auth.entity.AuthProvider;
import com.caloshape.backend.auth.repo.EmailLoginCodeRepository;
import com.caloshape.backend.entitlement.service.PlayReviewEntitlementService;
import com.caloshape.backend.fasting.service.FastingPlanService;
import com.caloshape.backend.fasting.support.ClientTimeZoneResolver;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;

@Service
public class EmailAuthService {

    private static final String PURPOSE_LOGIN = "LOGIN";

    private final EmailLoginCodeRepository codes;
    private final EmailOtpChallengeService otpChallenges;
    private final EmailAuthRateLimiter rateLimiter;
    private final UserRepo users;
    private final TokenService tokens;
    private final FastingPlanService fastingPlans;
    private final ClientTimeZoneResolver tzResolver;
    private final ApplicationEventPublisher events;
    private final PlayReviewAccessService playReviewAccess;
    private final PlayReviewEntitlementService playReviewEntitlements;

    public EmailAuthService(
            EmailLoginCodeRepository codes,
            EmailOtpChallengeService otpChallenges,
            EmailAuthRateLimiter rateLimiter,
            UserRepo users,
            TokenService tokens,
            FastingPlanService fastingPlans,
            ClientTimeZoneResolver tzResolver,
            ApplicationEventPublisher events,
            PlayReviewAccessService playReviewAccess,
            PlayReviewEntitlementService playReviewEntitlements
    ) {
        this.codes = codes;
        this.otpChallenges = otpChallenges;
        this.rateLimiter = rateLimiter;
        this.users = users;
        this.tokens = tokens;
        this.fastingPlans = fastingPlans;
        this.tzResolver = tzResolver;
        this.events = events;
        this.playReviewAccess = playReviewAccess;
        this.playReviewEntitlements = playReviewEntitlements;
    }

    @Value("${app.email.enabled:true}")
    boolean enabled;

    @Value("${app.email.otp-length:6}")
    int otpLen;

    @Value("${app.email.ttl-minutes:10}")
    int ttlMin;

    @Value("${app.auth.access-ttl-sec:900}")
    long accessTtlSeconds;

    @Value("${app.auth.refresh-ttl-sec:2592000}")
    long refreshTtlSeconds;

    @PostConstruct
    void validateConfiguration() {
        if (otpLen < 6) {
            throw new IllegalStateException("app.email.otp-length must be at least 6");
        }
        if (ttlMin < 1) {
            throw new IllegalStateException("app.email.ttl-minutes must be positive");
        }
    }

    @Transactional
    public StartResponse start(StartRequest req, String ip, String ua) {
        return start(req, null, ip, ua);
    }

    @Transactional
    public StartResponse start(StartRequest req, String deviceId, String ip, String ua) {
        if (!enabled) return new StartResponse(false);

        final String email = normalizeEmail(req.email());
        final Instant now = Instant.now();

        rateLimiter.checkStart(email, ip, deviceId, now);

        codes.consumeAllActive(email, PURPOSE_LOGIN, now);

        final String code = playReviewAccess.fixedCodeFor(email)
                .orElseGet(() -> EmailOtpCodeSupport.generateNumeric(otpLen));
        final String hash = EmailOtpCodeSupport.sha256(code);

        var ent = new EmailLoginCode();
        ent.setEmail(email);
        ent.setCodeHash(hash);
        ent.setPurpose(PURPOSE_LOGIN);
        ent.setCreatedAt(now);
        ent.setExpiresAt(now.plusSeconds(ttlMin * 60L));
        ent.setAttemptCnt(0);
        ent.setClientIp(ip);
        ent.setUserAgent(ua);
        codes.save(ent);

        if (!playReviewAccess.isReviewEmail(email)) {
            events.publishEvent(new EmailLoginCodeRequestedEvent(email, code, ttlMin));
        }

        return new StartResponse(true);
    }

    @Transactional
    public AuthResponse verify(VerifyRequest req, String deviceId, String ip, String ua) {
        final Instant now = Instant.now();
        final String email = normalizeEmail(req.email());

        rateLimiter.checkVerify(email, ip, deviceId, now);

        EmailOtpChallengeService.Result challengeResult = otpChallenges.verify(
                email,
                PURPOSE_LOGIN,
                req.code(),
                now
        );
        switch (challengeResult.status()) {
            case VERIFIED -> {
                // Continue with login completion below.
            }
            case TOO_MANY_ATTEMPTS -> throw new EmailAuthRateLimitException(
                    challengeResult.retryAfterSec()
            );
            case INVALID, INVALID_OR_EXPIRED -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired code"
            );
        }

        // 以 email 取回或建立帳號
        User user = users.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            return u;
        });

        user.setProvider(AuthProvider.EMAIL);
        user.setLastLoginAt(now);
        user = users.save(user);

        if (playReviewAccess.isReviewEmail(email)) {
            playReviewEntitlements.ensureActive(user.getId(), now, playReviewAccess.entitlementValidity());
        }

        // ✅ 不要在登入時預先建立 user_profiles
        //    否則前端 existsOnServer() 會把新帳號誤判成舊帳號，直接導去 HOME。

        // 只保留 fasting plan 預設建立
        String clientTz = tzResolver.resolveFromCurrentRequest();
        fastingPlans.ensureDefaultIfMissing(user.getId(), clientTz);

        var pair = tokens.issue(user, deviceId, ip, ua);

        return new AuthResponse(
                pair.accessToken(),
                pair.refreshToken(),
                "Bearer",
                accessTtlSeconds,
                refreshTtlSeconds,
                now.getEpochSecond()
        );
    }

    public AuthResponse verify(VerifyRequest req) {
        return verify(req, null, null, null);
    }

    private static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
