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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

@Service
public class EmailAuthService {

    private static final String PURPOSE_LOGIN = "LOGIN";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailLoginCodeRepository codes;
    private final UserRepo users;
    private final TokenService tokens;
    private final FastingPlanService fastingPlans;
    private final ClientTimeZoneResolver tzResolver;
    private final ApplicationEventPublisher events;
    private final PlayReviewAccessService playReviewAccess;
    private final PlayReviewEntitlementService playReviewEntitlements;

    public EmailAuthService(
            EmailLoginCodeRepository codes,
            UserRepo users,
            TokenService tokens,
            FastingPlanService fastingPlans,
            ClientTimeZoneResolver tzResolver,
            ApplicationEventPublisher events,
            PlayReviewAccessService playReviewAccess,
            PlayReviewEntitlementService playReviewEntitlements
    ) {
        this.codes = codes;
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

    @Value("${app.email.otp-length:4}")
    int otpLen;

    @Value("${app.email.ttl-minutes:10}")
    int ttlMin;

    @Value("${app.auth.access-ttl-sec:900}")
    long accessTtlSeconds;

    @Value("${app.auth.refresh-ttl-sec:2592000}")
    long refreshTtlSeconds;

    @Transactional
    public StartResponse start(StartRequest req, String ip, String ua) {
        if (!enabled) return new StartResponse(false);

        final String email = req.email().trim().toLowerCase();
        final Instant now = Instant.now();

        codes.consumeAllActive(email, PURPOSE_LOGIN, now);

        final String code = playReviewAccess.fixedCodeFor(email).orElseGet(() -> genCode(otpLen));
        final String hash = sha256(code);

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
        final String email = req.email().trim().toLowerCase();

        var latest = codes.findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                email, PURPOSE_LOGIN, now
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired or not found"));

        latest.setAttemptCnt((latest.getAttemptCnt() == null ? 0 : latest.getAttemptCnt()) + 1);
        codes.save(latest);

        if (!latest.getCodeHash().equals(sha256(req.code()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid code");
        }

        latest.setConsumedAt(now);
        codes.save(latest);

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

    private static String genCode(int len) {
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('0' + SECURE_RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
