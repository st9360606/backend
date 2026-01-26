package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.dto.EntitlementSyncResponse;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@RequiredArgsConstructor
@Service
public class EntitlementSyncService {

    private final SubscriptionVerifier verifier;
    private final UserEntitlementRepository entitlementRepo;
    private final BillingProductProperties productProps;

    @Transactional
    public EntitlementSyncResponse sync(Long userId, EntitlementSyncRequest req) {
        if (req == null || req.purchases() == null || req.purchases().isEmpty()) {
            return new EntitlementSyncResponse("INACTIVE", null);
        }

        // 1) 驗證所有 token，挑「最晚到期」那個（也可改成 YEARLY 優先）
        Instant bestExpiry = null;
        String bestProductId = null;
        String bestTokenHash = null;

        for (var p : req.purchases()) {
            String token = (p == null) ? null : p.purchaseToken();
            if (token == null || token.isBlank()) continue;

            SubscriptionVerifier.VerifiedSubscription v;
            try {
                v = verifier.verify(token);
            } catch (Exception ex) {
                // 驗證失敗就略過（不要讓整個登入流程卡住；你要嚴格也可以改成直接回 INACTIVE）
                continue;
            }

            if (!v.active() || v.expiryTimeUtc() == null || v.productId() == null) continue;

            if (bestExpiry == null || v.expiryTimeUtc().isAfter(bestExpiry)) {
                bestExpiry = v.expiryTimeUtc();
                bestProductId = v.productId();
                bestTokenHash = sha256Hex(token);
            }
        }

        // 2) 沒任何有效訂閱
        if (bestExpiry == null || bestProductId == null) {
            return new EntitlementSyncResponse("INACTIVE", null);
        }

        // 3) productId -> entitlementType（只允許白名單）
        String tier = productProps.toTierOrNull(bestProductId);
        if (tier == null) {
            return new EntitlementSyncResponse("INACTIVE", null);
        }

        Instant now = Instant.now();

        // 4) 先把這個 user 既有 ACTIVE 改成 EXPIRED（避免多筆 ACTIVE）
        entitlementRepo.expireActiveByUserId(userId, now);

        // 5) 插入新 ACTIVE entitlement（validTo=expiry）
        UserEntitlementEntity e = new UserEntitlementEntity();
        e.setUserId(userId);
        e.setEntitlementType(tier);
        e.setStatus("ACTIVE");
        e.setValidFromUtc(now);
        e.setValidToUtc(bestExpiry);
        e.setPurchaseTokenHash(bestTokenHash);
        e.setLastVerifiedAtUtc(now);

        entitlementRepo.save(e);

        return new EntitlementSyncResponse("ACTIVE", tier);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            return null;
        }
    }
}
