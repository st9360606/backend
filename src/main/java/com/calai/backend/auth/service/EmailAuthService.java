package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.StartRequest;
import com.calai.backend.auth.dto.StartResponse;
import com.calai.backend.auth.dto.VerifyRequest;
import com.calai.backend.auth.email.EmailLoginCode;
import com.calai.backend.auth.entity.AuthProvider;
import com.calai.backend.fasting.service.FastingPlanService;
import com.calai.backend.fasting.support.ClientTimeZoneResolver;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.auth.repo.EmailLoginCodeRepository;
import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.users.profile.service.UserProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Random;

@Service
public class EmailAuthService {

    private static final String PURPOSE_LOGIN = "LOGIN";

    private final EmailLoginCodeRepository codes;
    private final UserRepo users;
    private final JavaMailSender mail;
    private final TokenService tokens;
    private final UserProfileService profiles; // ★ 新增
    private final FastingPlanService fastingPlans;            // ★ 新增
    private final ClientTimeZoneResolver tzResolver;          // ★ 新增

    public EmailAuthService(
            EmailLoginCodeRepository codes,
            UserRepo users,
            JavaMailSender mail,
            TokenService tokens,
            UserProfileService profiles,
            FastingPlanService fastingPlans,
            ClientTimeZoneResolver tzResolver // ★ 新增
    ) {
        this.codes = codes;
        this.users = users;
        this.mail = mail;
        this.tokens = tokens;
        this.profiles = profiles; // ★ 新增
        this.fastingPlans = fastingPlans;
        this.tzResolver = tzResolver;
    }

    @Value("${app.email.enabled:true}") boolean enabled;
    @Value("${app.email.otp-length:4}") int otpLen;
    @Value("${app.email.ttl-minutes:10}") int ttlMin;
    @Value("${app.email.sender:no-reply@bitecal.app}") String sender;

    @Value("${app.auth.access-ttl-sec:900}") long accessTtlSeconds;
    @Value("${app.auth.refresh-ttl-sec:2592000}") long refreshTtlSeconds;

    @Transactional
    public StartResponse start(StartRequest req, String ip, String ua) {
        if (!enabled) return new StartResponse(false);

        final String email = req.email().trim().toLowerCase();
        final Instant now = Instant.now();

        codes.consumeAllActive(email, PURPOSE_LOGIN, now);

        final String code = genCode(otpLen);
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

        sendEmail(email, code);
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

        // ★ 登入即確保有一筆最小 Profile（避免前端第一拍遇到 404）
        profiles.ensureDefault(user);

        // 2) ★ 確保 fasting_plan 預設一筆（含時區）
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

    public AuthResponse verify(VerifyRequest req) { return verify(req, null, null, null); }

    // helpers
    private static String genCode(int len) {
        var r = new Random();
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendEmail(String to, String code) {
        var msg = new SimpleMailMessage();
        msg.setFrom(sender);
        msg.setTo(to);
        msg.setSubject("Your BiteCal login code");
        msg.setText("Your verification code is: " + code + "\nIt will expire in " + ttlMin + " minutes.");
        mail.send(msg);
    }
}
