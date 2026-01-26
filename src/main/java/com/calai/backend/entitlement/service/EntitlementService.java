package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@RequiredArgsConstructor
@Service
public class EntitlementService {

    public enum Tier { NONE, TRIAL, MONTHLY, YEARLY }

    private final UserEntitlementRepository repo;

    public Tier resolveTier(Long userId, Instant nowUtc) {
        var list = repo.findActive(userId, nowUtc, PageRequest.of(0, 5));
        if (list.isEmpty()) return Tier.NONE;

        // 若同時存在，取「最高」：YEARLY > MONTHLY > TRIAL
        Tier best = Tier.NONE;
        for (var e : list) {
            Tier t = parseTier(e.getEntitlementType());
            if (rank(t) > rank(best)) best = t;
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
