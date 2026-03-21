package com.calai.backend.foodlog.barcode.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class RedisBarcodeLock {

    private final StringRedisTemplate redis;
    private final String prefix;

    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "else return 0 end",
            Long.class
    );

    public RedisBarcodeLock(
            StringRedisTemplate redis,
            @Value("${app.openfoodfacts.redis-prefix:bitecal}") String prefix
    ) {
        this.redis = redis;
        this.prefix = (prefix == null || prefix.isBlank()) ? "bitecal" : prefix.trim();
    }

    public LockHandle tryLock(String norm, Duration ttl) {
        String key = prefix + ":off:lock:barcode:" + norm;
        String token = UUID.randomUUID().toString();

        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
        return (ok != null && ok) ? new LockHandle(key, token) : null;
    }

    public void unlock(LockHandle h) {
        if (h == null) return;
        redis.execute(UNLOCK, List.of(h.key()), h.token());
    }

    public record LockHandle(String key, String token) {}
}
