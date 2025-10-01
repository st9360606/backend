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

    /**
     * 發送登入驗證碼：
     * 1) 先把所有仍有效的舊碼「consume」掉
     * 2) 產出新碼、存 DB、寄出
     */
    @Transactional
    public StartResponse start(StartRequest req, String ip, String ua) {
        if (!enabled) return new StartResponse(false);

        String email = req.email().trim().toLowerCase();
        Instant now = Instant.now();

        // 先失效舊碼（避免同時存在多筆有效碼）
        codes.consumeAllActive(email, PURPOSE_LOGIN, now);

        // 產生新碼並存 DB
        String code = genCode(otpLen);
        String hash = sha256(code);

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

        // 寄信
        sendEmail(email, code);

        return new StartResponse(true);
    }

    /** ✅ 推薦用的四參數版本（Controller 呼叫這個） */
    @Transactional
    public AuthResponse verify(VerifyRequest req, String deviceId, String ip, String ua) {
        final Instant now = Instant.now();
        final String email = req.email().trim().toLowerCase();

        // 1) 只取最新一筆有效的驗證碼
        var latest = codes.findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
                        email, PURPOSE_LOGIN, now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired or not found"));

        // 2) 記一次嘗試
        latest.setAttemptCnt((latest.getAttemptCnt() == null ? 0 : latest.getAttemptCnt()) + 1);
        codes.save(latest);

        // 3) 比對
        if (!latest.getCodeHash().equals(sha256(req.code()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid code");
        }

        // 4) 成功：標記已使用，避免再次使用
        latest.setConsumedAt(now);
        codes.save(latest);

        // 5) upsert 使用者
        User user = users.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            return u;
        });
        user.setLastLoginAt(now);
        user = users.save(user);

        // 6) 發 Token
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

    /** ✅ 相容用的一參數版本（若其他地方仍只傳 VerifyRequest） */
    public AuthResponse verify(VerifyRequest req) {
        return verify(req, null, null, null);
    }

    // ===== helpers =====
    private static String genCode(int len) {
        var r = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('0' + r.nextInt(10)));
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
