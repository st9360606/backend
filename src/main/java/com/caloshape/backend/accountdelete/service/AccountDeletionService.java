package com.caloshape.backend.accountdelete.service;

import com.caloshape.backend.accountdelete.dto.AccountDeletionPreviewResponse;
import com.caloshape.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.caloshape.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.caloshape.backend.auth.repo.AuthTokenRepo;
import com.caloshape.backend.entitlement.entity.UserEntitlementEntity;
import com.caloshape.backend.entitlement.repo.UserEntitlementRepository;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@RequiredArgsConstructor
@Service
public class AccountDeletionService {

    private static final String GOOGLE_PLAY_SUBSCRIPTION_MANAGEMENT_URL =
            "https://play.google.com/store/account/subscriptions";

    private final AccountDeletionRequestRepository reqRepo;
    private final UserRepo userRepo;
    private final AuthTokenRepo authTokenRepo;
    private final UserEntitlementRepository entitlementRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AccountDeletionPreviewResponse getDeletionPreview(Long userId) {
        Instant now = Instant.now();
        UserEntitlementEntity active = findActiveEntitlement(userId, now);

        boolean hasActiveGooglePlaySubscription = isActiveGooglePlaySubscription(active);
        String premiumStatus = resolvePremiumStatus(active, now);

        return new AccountDeletionPreviewResponse(
                true,
                hasActiveGooglePlaySubscription,
                premiumStatus,
                active == null ? null : active.getEntitlementType(),
                active == null ? null : active.getValidToUtc(),
                GOOGLE_PLAY_SUBSCRIPTION_MANAGEMENT_URL,
                hasActiveGooglePlaySubscription
        );
    }

    @Transactional
    public AccountDeletionRequestEntity requestDeletion(
            Long userId,
            boolean subscriptionWarningAcknowledged,
            boolean userRequestedGooglePlayCancel
    ) {
        Instant now = Instant.now();
        UserEntitlementEntity active = findActiveEntitlement(userId, now);
        boolean hasActiveGooglePlaySubscription = isActiveGooglePlaySubscription(active);

        if (hasActiveGooglePlaySubscription && !subscriptionWarningAcknowledged) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "ACTIVE_GOOGLE_PLAY_SUBSCRIPTION_ACK_REQUIRED"
            );
        }

        // 1) upsert deletion request（冪等）
        var req = reqRepo.findByUserIdForUpdate(userId).orElseGet(AccountDeletionRequestEntity::new);
        req.setUserId(userId);
        req.setReqStatus("REQUESTED");
        req.setRequestedAtUtc(now);
        req.setSubscriptionWarningAcknowledged(subscriptionWarningAcknowledged);
        req.setUserRequestedGooglePlayCancel(userRequestedGooglePlayCancel);
        req.setHasActiveGooglePlaySubscriptionAtRequest(hasActiveGooglePlaySubscription);
        req.setActiveEntitlementTypeAtRequest(active == null ? null : active.getEntitlementType());
        req.setActiveEntitlementSourceAtRequest(active == null ? null : active.getSource());
        req.setActiveProductIdAtRequest(active == null ? null : active.getProductId());
        req.setActiveValidToUtcAtRequest(active == null ? null : active.getValidToUtc());
        reqRepo.save(req);

        // 2) 立即鎖帳 + 去識別（允許 email 回來新註冊）
        User u = userRepo.findByIdForUpdate(userId);
        if (u == null) throw new IllegalArgumentException("USER_NOT_FOUND");

        String email = u.getEmail();
        if (email != null && !email.isBlank()) {
            u.setDeletedEmailHash(sha256Hex(email.toLowerCase()));
            deleteEmailLoginCodes(email);
        }

        u.setStatus("DELETING");
        u.setDeletedAtUtc(now);

        // ✅ 關鍵：清空 email/google_sub/name/picture
        u.setEmail(null);
        u.setGoogleSub(null);
        u.setName(null);
        u.setPicture(null);

        userRepo.save(u);

        // 3) revoke tokens（強制登出）
        authTokenRepo.revokeAllByUserId(userId, now);

        return req;
    }

    private UserEntitlementEntity findActiveEntitlement(Long userId, Instant now) {
        return entitlementRepository
                .findActiveBestFirst(userId, now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isActiveGooglePlaySubscription(UserEntitlementEntity entitlement) {
        if (entitlement == null) {
            return false;
        }

        String source = entitlement.getSource();
        String type = entitlement.getEntitlementType();

        return "GOOGLE_PLAY".equalsIgnoreCase(source)
                && (
                "TRIAL".equalsIgnoreCase(type)
                        || "MONTHLY".equalsIgnoreCase(type)
                        || "YEARLY".equalsIgnoreCase(type)
        );
    }

    private String resolvePremiumStatus(UserEntitlementEntity active, Instant now) {
        if (active == null || active.getValidToUtc() == null || !active.getValidToUtc().isAfter(now)) {
            return "FREE";
        }

        if ("TRIAL".equalsIgnoreCase(active.getEntitlementType())) {
            return "TRIAL";
        }

        return "PREMIUM";
    }

    private void deleteEmailLoginCodes(String email) {
        jdbc.update("DELETE FROM email_login_codes WHERE email = ?", email.toLowerCase());
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
