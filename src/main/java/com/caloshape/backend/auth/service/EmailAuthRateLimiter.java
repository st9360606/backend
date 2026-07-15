package com.caloshape.backend.auth.service;

import com.caloshape.backend.auth.config.EmailAuthRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;

@Slf4j
@Service
public class EmailAuthRateLimiter {

    private static final RedisScript<String> RATE_CHECK_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                      redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
                    end
                    local ttl = redis.call('TTL', KEYS[1])
                    return tostring(current) .. ':' .. tostring(ttl)
                    """,
                    String.class
            );

    private record RedisResult(long currentCount, long ttlSec) {
    }

    private final StringRedisTemplate redis;
    private final String redisKeyPrefix;
    private final long windowSec;
    private final EmailAuthRateLimitProperties.Limits startLimits;
    private final EmailAuthRateLimitProperties.Limits verifyLimits;

    public EmailAuthRateLimiter(
            StringRedisTemplate redis,
            EmailAuthRateLimitProperties properties
    ) {
        this.redis = redis;
        this.redisKeyPrefix = normalizePrefix(properties.getRedisPrefix()) + ":auth:email";
        this.windowSec = requirePositiveWindow(properties.getWindow());
        this.startLimits = requirePositiveLimits(properties.getStart(), "start");
        this.verifyLimits = requirePositiveLimits(properties.getVerify(), "verify");
    }

    public void checkStart(String email, String ip, String deviceId, Instant now) {
        check("start", "email", email, startLimits.getEmailLimit(), now);
        check("start", "ip", ip, startLimits.getIpLimit(), now);
        check("start", "device", deviceId, startLimits.getDeviceLimit(), now);
    }

    public void checkVerify(String email, String ip, String deviceId, Instant now) {
        check("verify", "email", email, verifyLimits.getEmailLimit(), now);
        check("verify", "ip", ip, verifyLimits.getIpLimit(), now);
        check("verify", "device", deviceId, verifyLimits.getDeviceLimit(), now);
    }

    private void check(
            String endpoint,
            String dimension,
            String identifier,
            int limit,
            Instant now
    ) {
        String normalized = normalizeIdentifier(identifier);
        if (normalized.isEmpty()) {
            return;
        }

        long nowSec = now.getEpochSecond();
        long windowStart = (nowSec / windowSec) * windowSec;
        long expiresInSec = Math.max(1L, windowStart + windowSec - nowSec + 1L);
        String key = redisKeyPrefix
                + ':' + endpoint
                + ':' + dimension
                + ':' + sha256(normalized)
                + ':' + windowStart;

        String raw;
        try {
            raw = redis.execute(
                    RATE_CHECK_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(expiresInSec)
            );
        } catch (Exception ex) {
            log.error(
                    "email_auth_rate_limiter_unavailable endpoint={} dimension={} cause={}",
                    endpoint,
                    dimension,
                    ex.getClass().getSimpleName()
            );
            throw new EmailAuthUnavailableException(ex);
        }

        RedisResult result;
        try {
            result = parseRedisResult(raw);
        } catch (IllegalStateException ex) {
            log.error(
                    "email_auth_rate_limiter_invalid_result endpoint={} dimension={}",
                    endpoint,
                    dimension
            );
            throw new EmailAuthUnavailableException(ex);
        }

        if (result.currentCount() > limit) {
            int retryAfterSec = (int) Math.min(
                    Integer.MAX_VALUE,
                    Math.max(1L, result.ttlSec() > 0 ? result.ttlSec() : expiresInSec)
            );
            log.warn(
                    "email_auth_rate_limited endpoint={} dimension={} count={} limit={} retryAfterSec={}",
                    endpoint,
                    dimension,
                    result.currentCount(),
                    limit,
                    retryAfterSec
            );
            throw new EmailAuthRateLimitException(retryAfterSec);
        }
    }

    private static RedisResult parseRedisResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Redis rate limiter returned no result");
        }

        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Redis rate limiter returned an invalid result");
        }

        try {
            return new RedisResult(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Redis rate limiter returned non-numeric data", ex);
        }
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static String normalizePrefix(String raw) {
        String normalized = raw == null || raw.isBlank() ? "caloshape" : raw.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static long requirePositiveWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Email auth rate-limit window must be positive");
        }
        return window.getSeconds();
    }

    private static EmailAuthRateLimitProperties.Limits requirePositiveLimits(
            EmailAuthRateLimitProperties.Limits limits,
            String endpoint
    ) {
        if (limits == null
                || limits.getEmailLimit() < 1
                || limits.getIpLimit() < 1
                || limits.getDeviceLimit() < 1) {
            throw new IllegalArgumentException(
                    "Email auth " + endpoint + " rate limits must all be positive"
            );
        }
        return limits;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
