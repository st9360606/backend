package com.caloshape.backend.entitlement.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class EntitlementWorkerLease {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redis;
    private final String prefix;

    public EntitlementWorkerLease(
            StringRedisTemplate redis,
            @Value("${app.entitlement.worker-lease.redis-prefix:caloshape}") String prefix
    ) {
        this.redis = redis;
        this.prefix = prefix == null || prefix.isBlank() ? "caloshape" : prefix.trim();
    }

    public Lease tryAcquire(String workerName, Duration ttl) {
        String key = prefix + ":entitlement:worker:" + workerName;
        String ownerToken = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, ownerToken, ttl);
            return Boolean.TRUE.equals(acquired) ? new Lease(key, ownerToken) : null;
        } catch (RuntimeException ex) {
            log.warn(
                    "entitlement_worker_lease_unavailable worker={} errorType={}",
                    workerName,
                    ex.getClass().getSimpleName()
            );
            return null;
        }
    }

    public void release(Lease lease) {
        if (lease == null) return;
        try {
            redis.execute(RELEASE, List.of(lease.key()), lease.ownerToken());
        } catch (RuntimeException ex) {
            log.warn(
                    "entitlement_worker_lease_release_failed key={} errorType={}",
                    lease.key(),
                    ex.getClass().getSimpleName()
            );
        }
    }

    public record Lease(String key, String ownerToken) {}
}
