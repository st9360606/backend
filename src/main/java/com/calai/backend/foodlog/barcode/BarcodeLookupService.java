package com.calai.backend.foodlog.barcode;

import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class BarcodeLookupService {

    public record LookupResult(
            String barcodeRaw,
            String barcodeNorm,
            boolean found,
            boolean fromCache,
            String provider,
            OffResult off
    ) {}

    private final BarcodeCacheStore cacheStore;
    private final OpenFoodFactsClient offClient;

    private final OffGlobalRateLimiterRedis globalLimiter;
    private final RedisBarcodeLock lock;
    private final StringRedisTemplate redis;

    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final Duration lockTtl;

    private final Duration circuitTtl;
    private final String redisPrefix;

    /**
     * Redis fail-open 策略（建議先 false 保持既有行為）
     * - false: Redis 相關錯誤直接拋出（偏保守，保護 provider）
     * - true : Redis lock / limiter 掛掉時略過保護機制，繼續查 OFF（偏可用性）
     */
    private final boolean redisFailOpen;

    public BarcodeLookupService(
            BarcodeCacheStore cacheStore,
            OpenFoodFactsClient offClient,
            OffGlobalRateLimiterRedis globalLimiter,
            RedisBarcodeLock lock,
            StringRedisTemplate redis,

            @Value("${app.openfoodfacts.cache.positive-ttl:P30D}") Duration positiveTtl,
            @Value("${app.openfoodfacts.cache.negative-ttl:PT12H}") Duration negativeTtl,
            @Value("${app.openfoodfacts.lock-ttl:PT30S}") Duration lockTtl,

            @Value("${app.openfoodfacts.circuit-ttl:PT5M}") Duration circuitTtl,
            @Value("${app.openfoodfacts.redis-prefix:bitecal}") String redisPrefix,
            //Redis fail-open（Redis 掛掉時照樣查 OFF， redis-fail-open: true）
            @Value("${app.openfoodfacts.redis-fail-open:false}") boolean redisFailOpen
    ) {
        this.cacheStore = cacheStore;
        this.offClient = offClient;
        this.globalLimiter = globalLimiter;
        this.lock = lock;
        this.redis = redis;

        this.positiveTtl = positiveTtl;
        this.negativeTtl = negativeTtl;
        this.lockTtl = lockTtl;

        this.circuitTtl = circuitTtl;
        this.redisPrefix = (redisPrefix == null || redisPrefix.isBlank()) ? "bitecal" : redisPrefix.trim();
        this.redisFailOpen = redisFailOpen;
    }

    public LookupResult lookupOff(String rawBarcode, String preferredLangTag) {
        Instant now = Instant.now();

        var n = BarcodeNormalizer.normalizeOrThrow(rawBarcode);
        String norm = n.normalized();

        // 1) ✅ 先查 DB cache（命中就回，不受熔斷影響）
        var cached = cacheStore.readValid(norm, now);
        if (cached != null) return fromCache(n.rawInput(), norm, cached, preferredLangTag);

        // 2) ✅ 熔斷只擋「要打 OFF」的情況
        if (isCircuitOpen()) {
            throw circuitOpenRateLimited();
        }

        RedisBarcodeLock.LockHandle h = null;
        boolean lockBypassed = false;

        try {
            // 3) distributed lock (avoid stampede across pods)
            final int maxTries = 12;
            for (int i = 0; i < maxTries; i++) {

                try {
                    h = lock.tryLock(norm, lockTtl);
                } catch (Exception ex) {
                    if (redisFailOpen) {
                        // ✅ Redis lock 掛了：可配置為 fail-open，繼續主流程（不做 distributed lock）
                        log.warn("Redis barcode lock unavailable; bypass lock and continue. norm={}", norm, ex);
                        lockBypassed = true;
                        break;
                    }
                    // fail-closed（維持保守）
                    throw new OffHttpException(429, "PROVIDER_BUSY", "redis lock unavailable");
                }

                if (h != null) break;

                // someone else is fetching; wait with jitter and re-check cache
                sleepBackoff(i);

                cached = cacheStore.readValid(norm, Instant.now());
                if (cached != null) return fromCache(n.rawInput(), norm, cached, preferredLangTag);

                // 熔斷可能被別台打開
                if (isCircuitOpen()) {
                    throw circuitOpenRateLimited();
                }
            }

            // 若沒有拿到 lock 且也不是因為 fail-open 略過，表示 contention
            if (h == null && !lockBypassed) {
                throw new OffHttpException(429, "PROVIDER_BUSY", "barcode lookup contention");
            }

            // 4) double check cache（即使 lockBypassed 也值得再查一次）
            cached = cacheStore.readValid(norm, Instant.now());
            if (cached != null) return fromCache(n.rawInput(), norm, cached, preferredLangTag);

            // 5) 再確認熔斷（lockBypassed 情境同樣適用）
            if (isCircuitOpen()) {
                throw circuitOpenRateLimited();
            }

            // 6) global limiter (across pods) - protect your IP
            try {
                globalLimiter.acquireOrThrow(Instant.now());
            } catch (OffHttpException ex) {
                // ✅ 真的超限（或 limiter 明確拒絕）要照常丟，不能 fail-open
                throw ex;
            } catch (Exception ex) {
                if (redisFailOpen) {
                    // ✅ Redis limiter 掛了：可配置為 fail-open，繼續主流程
                    log.warn("OFF global limiter unavailable; bypass limiter and continue. norm={}", norm, ex);
                } else {
                    throw new OffHttpException(429, "PROVIDER_RATE_LIMITED", "redis global limiter unavailable");
                }
            }

            // 7) call OFF
            JsonNode root;
            try {
                root = offClient.getProduct(norm, OpenFoodFactsFields.fieldsFor(preferredLangTag));

            } catch (OffHttpException ex) {

                // 404：視為 NOT_FOUND（寫負快取，避免同壞條碼一直撞）
                if (ex.getStatus() == 404) {
                    root = notFoundSentinel();
                }
                // 429/403：打開熔斷，避免 ban/rate limit 後一直撞
                else if (ex.getStatus() == 429 || ex.getStatus() == 403) {
                    openCircuit();
                    throw new OffHttpException(
                            429,
                            "PROVIDER_RATE_LIMITED",
                            (ex.getBodySnippet() == null ? "" : ex.getBodySnippet())
                            + "; suggestedRetryAfterSec=" + safeCircuitRetryAfterSec()
                    );
                } else {
                    throw ex;
                }
            }

            OffResult off = OpenFoodFactsMapper.map(root, preferredLangTag);
            boolean found = (off != null);

            // 8) write cache
            Instant writeNow = Instant.now();
            Instant expiresAt = writeNow.plus(found ? positiveTtl : negativeTtl);

            cacheStore.saveOrUpdate(
                    norm,
                    n.rawInput(),
                    (root == null ? JsonNodeFactory.instance.nullNode() : root),
                    found,
                    writeNow,
                    expiresAt
            );

            return new LookupResult(n.rawInput(), norm, found, false, "OPENFOODFACTS", off);

        } finally {
            // ✅ 不讓 unlock 的 Redis 例外吃掉主流程例外
            try {
                lock.unlock(h);
            } catch (Exception ex) {
                log.warn("Failed to unlock redis barcode lock. norm={}", norm, ex);
            }
        }
    }

    // ---------------- circuit breaker ----------------

    private String circuitKey() {
        return redisPrefix + ":off:cb:global";
    }

    private boolean isCircuitOpen() {
        try {
            return redis.opsForValue().get(circuitKey()) != null;
        } catch (Exception ignore) {
            // Redis 掛了不要影響主流程
            return false;
        }
    }

    private void openCircuit() {
        try {
            redis.opsForValue().set(circuitKey(), "1", circuitTtl);
        } catch (Exception ignore) {
            // Redis 掛了就算了
        }
    }

    private OffHttpException circuitOpenRateLimited() {
        return new OffHttpException(
                429,
                "PROVIDER_RATE_LIMITED",
                "circuit open; suggestedRetryAfterSec=" + safeCircuitRetryAfterSec()
        );
    }

    private long safeCircuitRetryAfterSec() {
        long s = circuitTtl == null ? 0L : circuitTtl.getSeconds();
        return Math.max(1L, s);
    }

    private static JsonNode notFoundSentinel() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("status", 0);
        return n;
    }

    // ---------------- helpers ----------------

    private static void sleepBackoff(int attempt) {
        // 10ms, 20ms, 40ms... capped, + jitter
        long base = Math.min(200L, 10L * (1L << Math.min(attempt, 4)));
        long jitter = ThreadLocalRandom.current().nextLong(0, 25);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private LookupResult fromCache(String raw, String norm, BarcodeLookupCacheEntity cached, String preferredLangTag) {
        JsonNode root = cached.getPayload();
        OffResult off = OpenFoodFactsMapper.map(root, preferredLangTag);

        // ✅ 若 cache status=FOUND 但 payload 已無法映射，保守視為 miss-like result（found=false）
        boolean found = "FOUND".equalsIgnoreCase(cached.getStatus()) && off != null;

        if ("FOUND".equalsIgnoreCase(cached.getStatus()) && off == null) {
            log.warn("Barcode cache payload marked FOUND but mapper returned null. norm={}, provider={}",
                    norm, cached.getProvider());
        }

        return new LookupResult(raw, norm, found, true, cached.getProvider(), off);
    }
}