package com.calai.backend.foodlog.barcode;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface BarcodeLookupCacheRepository extends JpaRepository<BarcodeLookupCacheEntity, String> {
    List<BarcodeLookupCacheEntity> findTop1000ByExpiresAtUtcBeforeOrderByExpiresAtUtcAsc(Instant now);
}

