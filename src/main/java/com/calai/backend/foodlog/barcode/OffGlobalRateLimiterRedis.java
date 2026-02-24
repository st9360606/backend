package com.calai.backend.foodlog.barcode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class OffGlobalRateLimiterRedis {

    private final StringRedisTemplate redis;
    private final int limitPerMin;
    private final String prefix;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); " +
            "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]); end; " +
            "if n > tonumber(ARGV[1]) then return -1; end; " +
            "return n;",
            Long.class
    );

    public OffGlobalRateLimiterRedis(
            StringRedisTemplate redis,
            @Value("${app.openfoodfacts.global-read-limit-per-min:90}") int limitPerMin,
            @Value("${app.openfoodfacts.redis-prefix:bitecal}") String prefix
    ) {
        this.redis = redis;
        this.limitPerMin = Math.max(1, limitPerMin);
        this.prefix = (prefix == null || prefix.isBlank()) ? "bitecal" : prefix.trim();
    }

    public void acquireOrThrow(Instant now) {
        long minute = now.getEpochSecond() / 60;
        String key = prefix + ":off:rl:product:" + minute;

        Long r = redis.execute(SCRIPT, List.of(key),
                String.valueOf(limitPerMin),
                "65"
        );

        if (r == null || r.longValue() < 0) {
            throw new OffHttpException(429, "OFF_GLOBAL_RATE_LIMIT", "redis global limiter exceeded");
        }
    }
}
