package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@RequiredArgsConstructor
@Slf4j
@Service
public class EntitlementService {

    public enum Tier { NONE, TRIAL, MONTHLY, YEARLY }

    private final UserEntitlementRepository repo;

    public Tier resolveTier(Long userId, Instant nowUtc) {
        var list = repo.findActive(userId, nowUtc, PageRequest.of(0, 5));

        if (log.isDebugEnabled()) {
            log.debug("resolveTier userId={} nowUtc={} activeEntitlements={}", userId, nowUtc, list.size());
        }

        if (list.size() > 1) {
            log.warn("multiple_active_entitlements userId={} count={}", userId, list.size());
        }

        if (list.isEmpty()) return Tier.NONE;

        Tier best = Tier.NONE;
        for (var e : list) {
            Tier t = parseTier(e.getEntitlementType());

            if (t == Tier.NONE && e.getEntitlementType() != null && !e.getEntitlementType().isBlank()) {
                log.warn("unknown_entitlement_type userId={} entitlementId={} rawType={}",
                        userId, e.getId(), e.getEntitlementType());
            }

            if (log.isDebugEnabled()) {
                log.debug(
                        "entitlement id={} type={} status={} validFrom={} validTo={} parsedTier={}",
                        e.getId(),
                        e.getEntitlementType(),
                        e.getStatus(),
                        e.getValidFromUtc(),
                        e.getValidToUtc(),
                        t
                );
            }
            if (rank(t) > rank(best)) best = t;
        }
        if (log.isDebugEnabled()) {
            log.debug("resolveTier result userId={} tier={}", userId, best);
        }
        return best;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return Tier.NONE;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "TRIAL" -> Tier.TRIAL;
            case "MONTHLY" -> Tier.MONTHLY;
            case "YEARLY" -> Tier.YEARLY;
            default -> Tier.NONE;
        };
    }

    private static int rank(Tier t) {
        return switch (t) {
            case NONE -> 0;
            case TRIAL -> 1;
            case MONTHLY -> 2;
            case YEARLY -> 3;
        };
    }
}
