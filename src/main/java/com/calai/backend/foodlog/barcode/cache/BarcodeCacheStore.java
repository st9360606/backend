package com.calai.backend.foodlog.barcode.cache;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class BarcodeCacheStore {

    private final BarcodeLookupCacheRepository repo;

    public BarcodeCacheStore(BarcodeLookupCacheRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly=true, propagation = Propagation.SUPPORTS)
    public BarcodeLookupCacheEntity readValid(String norm, Instant now) {
        var e = repo.findById(norm).orElse(null);
        if (e == null) return null;
        if (e.getExpiresAtUtc() == null) return null;
        return e.getExpiresAtUtc().isAfter(now) ? e : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOrUpdate(
            String norm,
            String raw,
            JsonNode root,
            boolean found,
            Instant now,
            Instant expiresAt
    ) {
        BarcodeLookupCacheEntity e = repo.findById(norm).orElse(null);
        if (e == null) {
            e = new BarcodeLookupCacheEntity();
            e.setBarcodeNorm(norm);
            e.setCreatedAtUtc(now); // ✅ 明確寫入，避免 merge 時變 null
        }

        e.setBarcodeRawExample(raw);
        e.setProvider("OPENFOODFACTS");
        e.setStatus(found ? "FOUND" : "NOT_FOUND");
        e.setPayload(root);
        e.setExpiresAtUtc(expiresAt);
        e.setUpdatedAtUtc(now);

        repo.save(e);
    }
}
