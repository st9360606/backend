package com.calai.backend.auth.service;

import com.calai.backend.auth.dto.AuthResponse;
import com.calai.backend.auth.dto.StartRequest;
import com.calai.backend.auth.dto.StartResponse;
import com.calai.backend.auth.dto.VerifyRequest;
import com.calai.backend.auth.email.EmailLoginCode;
import com.calai.backend.auth.entity.User;         // ← 確認這是你專案中 User 的正確路徑
import com.calai.backend.auth.repo.EmailLoginCodeRepository;
import com.calai.backend.auth.repo.UserRepo;      // ← 確認這是你專案中 Repo 的正確路徑
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Random;

@Service
public class EmailAuthService {

    private final EmailLoginCodeRepository codes;
    private final UserRepo users;
    private final JavaMailSender mail;
    private final TokenService tokens;            // ← 你的 TokenService

    public EmailAuthService(
            EmailLoginCodeRepository codes,
            UserRepo users,
            JavaMailSender mail,
            TokenService tokens
    ) {
        this.codes = codes;
        this.users = users;
        this.mail = mail;
        this.tokens = tokens;
    }

    @Value("${app.email.enabled:true}") boolean enabled;
    @Value("${app.email.otp-length:4}") int otpLen;
    @Value("${app.email.ttl-minutes:10}") int ttlMin;
    @Value("${app.email.sender:no-reply@bitecal.app}") String sender;

    @Value("${app.auth.access-ttl-sec:900}") long accessTtlSeconds;
    @Value("${app.auth.refresh-ttl-sec:2592000}") long refreshTtlSeconds;

    public StartResponse start(StartRequest req, String ip, String ua) {
        if (!enabled) return new StartResponse(false);

        String email = req.email().trim().toLowerCase();
        String code = genCode(otpLen);
        String hash = sha256(code);

        var now = Instant.now();
        var ent = new EmailLoginCode();
        ent.setEmail(email);
        ent.setCodeHash(hash);
        ent.setPurpose("LOGIN");
        ent.setCreatedAt(now);
        ent.setExpiresAt(now.plusSeconds(ttlMin * 60L));
        ent.setAttemptCnt(0);
        ent.setClientIp(ip);
        ent.setUserAgent(ua);
        codes.save(ent);

        sendEmail(email, code);
        return new StartResponse(true);
    }

    /** ✅ 推薦用的四參數版本（Controller 會呼叫這個） */
    public AuthResponse verify(VerifyRequest req, String deviceId, String ip, String ua) {
        var now = Instant.now();
        var email = req.email().trim().toLowerCase();

        var latest = codes.findLatestActive(email, now)
                .orElseThrow(() -> new IllegalArgumentException("No active code"));

        latest.setAttemptCnt((latest.getAttemptCnt()==null?0:latest.getAttemptCnt()) + 1);
        codes.save(latest);

        if (!latest.getCodeHash().equals(sha256(req.code()))) {
            throw new IllegalArgumentException("Invalid code");
        }
        latest.setConsumedAt(now);
        codes.save(latest);

        // upsert user by email
        User user = users.findByEmail(email).orElseGet(() -> {
            var u = new User();
            u.setEmail(email);
            u.setName(null);
            u.setPicture(null);
            u.setLastLoginAt(now);
            return users.save(u);
        });
        user.setLastLoginAt(now);
        users.save(user);

        // 透過你的 TokenService 發 token（依你的回傳型別調整）
        var pair = tokens.issue(user, deviceId, ip, ua); // 假設回傳有 accessToken()/refreshToken()

        return new AuthResponse(
                pair.accessToken(),
                pair.refreshToken(),
                "Bearer",
                accessTtlSeconds,
                refreshTtlSeconds,
                now.getEpochSecond()
        );
    }

    /** ✅ 相容用的一參數版本（若其他地方仍舊只傳 VerifyRequest，也能編過） */
    public AuthResponse verify(VerifyRequest req) {
        return verify(req, null, null, null);
    }

    // ===== helpers =====
    private static String genCode(int len) {
        var r = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i=0;i<len;i++) sb.append((char)('0'+ r.nextInt(10)));
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
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
