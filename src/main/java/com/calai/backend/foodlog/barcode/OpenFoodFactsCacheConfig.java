package com.calai.backend.foodlog.barcode;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenFoodFactsCacheConfig {

    @Bean("offCacheManager")
    public CacheManager offCacheManager(
            @Value("${app.openfoodfacts.cache.ttl:PT6H}") Duration ttl,
            @Value("${app.openfoodfacts.cache.max-size:50000}") long maxSize
    ) {
        CaffeineCacheManager mgr = new CaffeineCacheManager("offProduct");
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
        );
        return mgr;
    }
}
