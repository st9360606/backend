package com.calai.backend.foodlog.barcode.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class BarcodeCachePurger {

    private final BarcodeLookupCacheRepository repo;
    private final BarcodeCachePurgerTx tx;

    @Scheduled(fixedDelayString = "${app.openfoodfacts.cache.purge-delay:PT1H}")
    public void purgeExpired() {
        Instant now = Instant.now();
        int total = 0;

        while (true) {
            List<BarcodeLookupCacheEntity> batch =
                    repo.findTop1000ByExpiresAtUtcBeforeOrderByExpiresAtUtcAsc(now);

            if (batch.isEmpty()) break;

            int deleted = tx.deleteBatch(batch); // ✅ 每批獨立交易
            total += deleted;

            if (total >= 100_000) break;
        }

        if (total > 0) log.info("Purged {} expired barcode cache rows", total);
    }
}
