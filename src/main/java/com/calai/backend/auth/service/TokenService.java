package com.calai.backend.auth.service;


import com.calai.backend.auth.entity.AuthToken;
import com.calai.backend.users.entity.User;
import com.calai.backend.auth.repo.AuthTokenRepo;
import com.calai.backend.auth.utils.SecureToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TokenService {

    private final AuthTokenRepo repo;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public TokenService(
            AuthTokenRepo repo,
            @Value("${app.auth.access-ttl-sec:900}") long accessTtlSeconds,     // 15 分
            @Value("${app.auth.refresh-ttl-sec:2592000}") long refreshTtlSeconds // 30 天
    ) {
        this.repo = repo;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @Transactional
    public AuthPair issue(User user, String deviceId, String ip, String ua) {
        String at = SecureToken.newTokenHex(32);
        String rt = SecureToken.newTokenHex(32);
        var now = Instant.now();

        var access = new AuthToken();
        access.setToken(at);
        access.setUser(user);
        access.setType(AuthToken.TokenType.ACCESS);
        access.setExpiresAt(now.plusSeconds(accessTtlSeconds));
        access.setClientIp(ip); access.setUserAgent(ua); access.setDeviceId(deviceId);
        repo.save(access);

        var refresh = new AuthToken();
        refresh.setToken(rt);
        refresh.setUser(user);
        refresh.setType(AuthToken.TokenType.REFRESH);
        refresh.setExpiresAt(now.plusSeconds(refreshTtlSeconds));
        refresh.setClientIp(ip); refresh.setUserAgent(ua); refresh.setDeviceId(deviceId);
        repo.save(refresh);

        return new AuthPair(at, rt);
    }

    @Transactional
    public AuthPair rotateRefresh(String oldRefreshToken, String deviceId, String ip, String ua) {
        var tk = repo.findByToken(oldRefreshToken)
                .orElseThrow(() -> new IllegalArgumentException("refresh token not found"));
        if (tk.isRevoked() || tk.getType() != AuthToken.TokenType.REFRESH
                || tk.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("refresh token invalid");
        }
        // 旋轉：撤銷舊 RT，發新 AT/RT
        tk.setRevoked(true);
        var pair = issue(tk.getUser(), deviceId, ip, ua);
        tk.setReplacedBy(pair.refreshToken());
        return pair;
    }

    public User validateAccess(String token) {
        var tk = repo.findByToken(token).orElse(null);
        if (tk == null || tk.isRevoked() || tk.getType() != AuthToken.TokenType.ACCESS) return null;
        if (tk.getExpiresAt().isBefore(Instant.now())) return null;
        return tk.getUser();
    }

    @Transactional
    public void revokeToken(String token) {
        repo.findByToken(token).ifPresent(t -> { t.setRevoked(true); repo.save(t); });
    }

    public record AuthPair(String accessToken, String refreshToken) {}
}
