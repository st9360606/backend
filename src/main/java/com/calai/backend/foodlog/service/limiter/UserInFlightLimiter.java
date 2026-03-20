package com.calai.backend.foodlog.service.limiter;

import com.calai.backend.foodlog.web.error.TooManyInFlightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * ✅ Redis 多機全域 in-flight limiter
 *
 * 設計：
 * - 每個 userId 一個 ZSET key
 * - member = lease token(UUID)
 * - score  = expiresAtEpochMillis
 * - acquire 前會先清理已過期 lease
 * - release 會刪掉對應 token
 *
 * 優點：
 * 1. 多機全域生效
 * 2. request crash 時，lease TTL 到期後可自動回收
 * 3. 避免單純 counter 因漏 release 而永久卡死
 */
@Slf4j
@Service
public class UserInFlightLimiter {

    public record Lease(Long userId, String token) {}

    /**
     * 回傳格式："{okFlag}:{currentCount}:{retryAfterSec}"
     * - okFlag = 1 代表 acquire 成功
     * - okFlag = 0 代表 acquire 失敗
     * - retryAfterSec：失敗時表示最早可重試秒數；成功時固定為 0
     * <p>
     * ARGV[1] = nowMillis
     * ARGV[2] = maxInFlight
     * ARGV[3] = expiresAtMillis
     * ARGV[4] = token
     * ARGV[5] = leaseTtlMillis
     */
    private static final RedisScript<String> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                            local nowMs = tonumber(ARGV[1])
                            local maxInFlight = tonumber(ARGV[2])
                            local expiresAtMs = tonumber(ARGV[3])
                            local token = ARGV[4]
                            local leaseTtlMs = tonumber(ARGV[5])
                            
                            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMs)
                            
                            local cnt = redis.call('ZCARD', KEYS[1])
                            if cnt >= maxInFlight then
                              local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                              local retryAfterSec = 1
                            
                              if first ~= nil and #first >= 2 then
                                local earliestExpireMs = tonumber(first[2])
                                if earliestExpireMs ~= nil and earliestExpireMs > nowMs then
                                  retryAfterSec = math.max(1, math.ceil((earliestExpireMs - nowMs) / 1000))
                                end
                              end
                            
                              return '0:' .. tostring(cnt) .. ':' .. tostring(retryAfterSec)
                            end
                            
                            redis.call('ZADD', KEYS[1], expiresAtMs, token)
                            redis.call('PEXPIRE', KEYS[1], leaseTtlMs)
                            
                            return '1:' .. tostring(cnt + 1) .. ':0'
                            """,
                    String.class
            );

    /**
     * 回傳格式："{removed}:{remainingCount}"
     *
     * ARGV[1] = nowMillis
     * ARGV[2] = token
     * ARGV[3] = leaseTtlMillis
     */
    private static final RedisScript<String> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local nowMs = tonumber(ARGV[1])
                    local token = ARGV[2]
                    local leaseTtlMs = tonumber(ARGV[3])

                    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMs)

                    local removed = redis.call('ZREM', KEYS[1], token)
                    local cnt = redis.call('ZCARD', KEYS[1])

                    if cnt == 0 then
                      redis.call('DEL', KEYS[1])
                    else
                      redis.call('PEXPIRE', KEYS[1], leaseTtlMs)
                    end

                    return tostring(removed) .. ':' .. tostring(cnt)
                    """,
                    String.class
            );

    private final int maxInFlight;
    private final long leaseTtlMillis;
    private final String redisKeyPrefix;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;

    public UserInFlightLimiter(
            @Value("${app.guard.inflight.max:2}") int maxInFlight,
            @Value("${app.guard.inflight.lease-ttl:PT5M}") Duration leaseTtl,
            @Value("${app.guard.inflight.redis-prefix:bitecal}") String redisPrefix,
            Clock clock,
            StringRedisTemplate redisTemplate
    ) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.leaseTtlMillis = Math.max(30_000L, leaseTtl.toMillis());
        this.redisKeyPrefix = normalizePrefix(redisPrefix) + ":guard:inflight";
        this.clock = clock;
        this.redisTemplate = redisTemplate;
    }

    public Lease acquireOrThrow(Long userId) {
        if (userId == null) return null;

        long nowMs = clock.instant().toEpochMilli();
        long expiresAtMs = nowMs + leaseTtlMillis;
        String token = UUID.randomUUID().toString();
        String key = inFlightKey(userId);

        String raw;
        try {
            raw = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(nowMs),
                    String.valueOf(maxInFlight),
                    String.valueOf(expiresAtMs),
                    token,
                    String.valueOf(leaseTtlMillis)
            );
        } catch (Exception ex) {
            log.error("inflight_acquire_redis_error userId={} key={} message={}",
                    userId, key, ex.getMessage(), ex);
            throw new IllegalStateException("INFLIGHT_LIMITER_REDIS_FAILED", ex);
        }

        if (raw == null || raw.isBlank()) {
            log.error("inflight_acquire_failed userId={} key={} reason=empty_redis_result", userId, key);
            throw new IllegalStateException("INFLIGHT_LIMITER_REDIS_FAILED");
        }

        String[] parts = raw.split(":", 3);
        if (parts.length != 3) {
            log.error("inflight_acquire_failed userId={} key={} raw={}", userId, key, raw);
            throw new IllegalStateException("INFLIGHT_LIMITER_REDIS_FAILED");
        }

        if ("1".equals(parts[0])) {
            if (log.isDebugEnabled()) {
                log.debug("inflight_acquired userId={} key={} token={} expiresAtMs={}",
                        userId, key, token, expiresAtMs);
            }
            return new Lease(userId, token);
        }

        int retryAfterSec = 1;
        try {
            retryAfterSec = Math.max(1, Integer.parseInt(parts[2]));
        } catch (NumberFormatException ignored) {
            // fallback 維持 1 秒
        }

        log.warn("too_many_inflight userId={} key={} retryAfterSec={}", userId, key, retryAfterSec);
        throw new TooManyInFlightException("TOO_MANY_IN_FLIGHT", retryAfterSec, "RETRY_LATER");
    }

    /**
     * release 採 best effort：
     * - 不拋例外，避免 finally 區塊把主流程結果蓋掉
     * - 若 Redis 暫時故障，最差情況是 lease 等 TTL 到期自動清掉
     */
    public void release(Lease lease) {
        if (lease == null || lease.userId() == null || lease.token() == null || lease.token().isBlank()) {
            return;
        }

        long nowMs = clock.instant().toEpochMilli();
        String key = inFlightKey(lease.userId());

        try {
            String raw = redisTemplate.execute(
                    RELEASE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(nowMs),
                    lease.token(),
                    String.valueOf(leaseTtlMillis)
            );

            if (log.isDebugEnabled()) {
                log.debug("inflight_released userId={} key={} token={} result={}",
                        lease.userId(), key, lease.token(), raw);
            }
        } catch (Exception ex) {
            log.warn("inflight_release_failed userId={} key={} token={} message={}",
                    lease.userId(), key, lease.token(), ex.getMessage(), ex);
        }
    }

    private String inFlightKey(Long userId) {
        return redisKeyPrefix + ":user:" + userId;
    }

    private static String normalizePrefix(String raw) {
        String s = (raw == null || raw.isBlank()) ? "bitecal" : raw.trim();
        while (s.endsWith(":")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
