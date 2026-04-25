package com.calai.backend.entitlement.service;

import com.calai.backend.entitlement.dto.EntitlementSyncRequest;
import com.calai.backend.entitlement.dto.EntitlementSyncResponse;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.calai.backend.referral.service.ReferralBillingBridgeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@RequiredArgsConstructor
@Service
public class EntitlementSyncService {

    private final SubscriptionVerifier verifier;
    private final UserEntitlementRepository entitlementRepo;
    private final BillingProductProperties productProps;
    private final ReferralBillingBridgeService referralBillingBridgeService;

    @Transactional
    public EntitlementSyncResponse sync(Long userId, EntitlementSyncRequest req) {
        if (req == null || req.purchases() == null || req.purchases().isEmpty()) {
            return buildSummaryResponse(userId, Instant.now());
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
        if (bestExpiry == null || bestProductId == null || bestTokenHash == null || bestTokenHash.isBlank()) {
            return buildSummaryResponse(userId, Instant.now());
        }

        // 3) productId -> entitlementType（只允許白名單）
        String tier = productProps.toTierOrNull(bestProductId);
        if (tier == null) {
            return buildSummaryResponse(userId, Instant.now());
        }

        Instant now = Instant.now();

        // 4) 付款成功後，先把這個 user 既有 ACTIVE entitlement 全部改成 EXPIRED。
        // 包含：
        // - 舊 TRIAL ACTIVE
        // - 舊 MONTHLY ACTIVE
        // - 舊 YEARLY ACTIVE
        // 這樣可以保證付款成功後只會有一筆新的 paid ACTIVE entitlement。
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

        // invitee 如果之前有輸入邀請碼，首次有效付費訂閱後進入 7 天驗證期。
        // 目前 SubscriptionVerifier 還沒有回 pending/test/autoRenew 細節，先用保守預設值。
        // 下一步接 RTDN / Google Play API 狀態時，要把 pending/test/refund 正確映射進來。
        referralBillingBridgeService.onFirstPaidSubscriptionVerified(
                userId,
                bestTokenHash,
                now,
                true,
                false,
                false
        );

        return buildSummaryResponse(userId, now);
    }

    @Transactional(readOnly = true)
    public EntitlementSyncResponse me(Long userId) {
        return buildSummaryResponse(userId, Instant.now());
    }

    private EntitlementSyncResponse buildSummaryResponse(Long userId, Instant now) {
        var activeList = entitlementRepo.findActiveBestFirst(userId, now, PageRequest.of(0, 1));

        if (activeList.isEmpty()) {
            return new EntitlementSyncResponse(
                    "INACTIVE",
                    null,
                    "FREE",
                    null,
                    null,
                    null
            );
        }

        UserEntitlementEntity e = activeList.get(0);
        String type = e.getEntitlementType();
        Instant validTo = e.getValidToUtc();

        if ("TRIAL".equalsIgnoreCase(type)) {
            return new EntitlementSyncResponse(
                    "ACTIVE",
                    "TRIAL",
                    "TRIAL",
                    validTo,
                    validTo,
                    calcDaysLeft(now, validTo)
            );
        }

        return new EntitlementSyncResponse(
                "ACTIVE",
                type,
                "PREMIUM",
                validTo,
                null,
                null
        );
    }

    private static int calcDaysLeft(Instant now, Instant end) {
        if (end == null || !end.isAfter(now)) return 0;
        long seconds = Duration.between(now, end).getSeconds();
        return (int) Math.max(1, Math.ceil(seconds / 86_400.0));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA256_FAILED", e);
        }
    }
}
