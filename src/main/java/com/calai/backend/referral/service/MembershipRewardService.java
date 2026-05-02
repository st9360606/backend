package com.calai.backend.referral.service;

import com.calai.backend.entitlement.entity.UserEntitlementEntity;
import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.GooglePlaySubscriptionDeferralService;
import com.calai.backend.entitlement.service.PurchaseTokenCrypto;
import com.calai.backend.referral.entity.MembershipRewardLedgerEntity;
import com.calai.backend.referral.repo.MembershipRewardLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class MembershipRewardService {

    public record RewardGrantResult(
            Instant oldPremiumUntil,
            Instant newPremiumUntil,
            Instant grantedAtUtc
    ) {}

    public static class RewardGrantDeferredException extends Exception {
        public RewardGrantDeferredException(String message) {
            super(message);
        }

        public RewardGrantDeferredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RewardGrantFinalException extends Exception {
        private final String rejectReason;

        public RewardGrantFinalException(String message, String rejectReason) {
            super(message);
            this.rejectReason = rejectReason;
        }

        public String rejectReason() {
            return rejectReason;
        }
    }

    private static final int DAYS_ADDED = 30;

    private static final String SOURCE_TYPE_REFERRAL_SUCCESS = "REFERRAL_SUCCESS";

    private static final String GRANT_STATUS_SUCCESS = "SUCCESS";
    private static final String GRANT_STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE";
    private static final String GRANT_STATUS_FAILED_FINAL = "FAILED_FINAL";
    private static final String GRANT_STATUS_IN_PROGRESS = "GOOGLE_DEFER_IN_PROGRESS";

    private static final String CHANNEL_GOOGLE_PLAY_DEFER = "GOOGLE_PLAY_DEFER";
    private static final String CHANNEL_BACKEND_ONLY = "BACKEND_ONLY";

    private static final int MAX_GOOGLE_DEFER_ATTEMPTS = 5;
    private static final long GOOGLE_DEFER_FINAL_AFTER_SECONDS = 24L * 60L * 60L;
    private static final long GOOGLE_DEFER_IN_PROGRESS_TTL_SECONDS = 15L * 60L;
    private static final long GOOGLE_DEFER_RECONCILE_THRESHOLD_SECONDS = 25L * 24L * 60L * 60L;

    private final UserEntitlementRepository entitlementRepository;
    private final MembershipRewardLedgerRepository rewardLedgerRepository;
    private final PurchaseTokenCrypto purchaseTokenCrypto;
    private final ObjectProvider<GooglePlaySubscriptionDeferralService> googleDeferralProvider;
    private final MembershipRewardAttemptTxService attemptTxService;

    /**
     * 不要加 @Transactional。
     * 原因：
     * Google Play defer 是不可 rollback 的外部副作用。
     * 如果把 Google API call 包在同一個 DB transaction 裡，
     * 可能發生 Google defer 成功但 DB rollback，下一輪又 defer 一次。
     */
    public RewardGrantResult grantReferralReward(Long inviterUserId, Long claimId)
            throws RewardGrantDeferredException, RewardGrantFinalException {
        MembershipRewardLedgerEntity success = rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdAndGrantStatusOrderByGrantedAtUtcDesc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId,
                        GRANT_STATUS_SUCCESS
                )
                .orElse(null);

        if (success != null) {
            return new RewardGrantResult(
                    success.getOldPremiumUntil(),
                    success.getNewPremiumUntil(),
                    success.getGrantedAtUtc()
            );
        }

        Instant now = Instant.now();

        UserEntitlementEntity googlePaid = entitlementRepository
                .findAnyActivePaidGooglePlayForReferral(inviterUserId, now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);

        if (googlePaid != null) {
            return grantGooglePlayDeferredReward(inviterUserId, claimId, googlePaid, now);
        }

        return grantBackendOnlyReferralReward(inviterUserId, claimId, now);
    }

    private RewardGrantResult grantGooglePlayDeferredReward(
            Long inviterUserId,
            Long claimId,
            UserEntitlementEntity googlePaid,
            Instant now
    ) throws RewardGrantDeferredException, RewardGrantFinalException {
        GooglePlaySubscriptionDeferralService googleDeferral = googleDeferralProvider.getIfAvailable();

        if (googleDeferral == null) {
            saveAttemptLedger(
                    inviterUserId,
                    claimId,
                    googlePaid.getValidToUtc(),
                    null,
                    googlePaid.getPurchaseTokenHash(),
                    GRANT_STATUS_FAILED_RETRYABLE,
                    CHANNEL_GOOGLE_PLAY_DEFER,
                    "FAILED_RETRYABLE",
                    null,
                    null,
                    null,
                    "GOOGLE_DEFER_SERVICE_UNAVAILABLE",
                    "Google Play deferral service is not available",
                    now.plusSeconds(10L * 60L),
                    now
            );
            throw new RewardGrantDeferredException("GOOGLE_DEFER_SERVICE_UNAVAILABLE");
        }

        String rawPurchaseToken = purchaseTokenCrypto.decryptOrNull(googlePaid.getPurchaseTokenCiphertext());

        if (rawPurchaseToken == null || rawPurchaseToken.isBlank()) {
            saveAttemptLedger(
                    inviterUserId,
                    claimId,
                    googlePaid.getValidToUtc(),
                    null,
                    googlePaid.getPurchaseTokenHash(),
                    GRANT_STATUS_FAILED_FINAL,
                    CHANNEL_GOOGLE_PLAY_DEFER,
                    "FAILED_FINAL",
                    null,
                    null,
                    null,
                    "GOOGLE_PURCHASE_TOKEN_NOT_DECRYPTABLE",
                    "Google Play purchase token cannot be decrypted. Do not fallback to backend-only reward for Google Play paid subscribers.",
                    null,
                    now
            );
            throw new RewardGrantFinalException(
                    "GOOGLE_PURCHASE_TOKEN_NOT_DECRYPTABLE",
                    "REWARD_GRANT_FAILED"
            );
        }

        Optional<RewardGrantResult> reconciled =
                reconcileStaleGoogleDeferInProgress(
                        inviterUserId,
                        claimId,
                        googlePaid,
                        rawPurchaseToken,
                        googleDeferral,
                        now
                );

        if (reconciled.isPresent()) {
            return reconciled.get();
        }

        enforceGoogleDeferRetryPolicy(inviterUserId, claimId, googlePaid, now);

        Instant oldPremiumUntil = googlePaid.getValidToUtc();

        MembershipRewardLedgerEntity inProgress =
                startGoogleDeferAttempt(inviterUserId, claimId, googlePaid, now);

        try {
            GooglePlaySubscriptionDeferralService.DeferralResult deferred =
                    googleDeferral.deferBy30Days(rawPurchaseToken);

            Instant newPremiumUntil = deferred.newExpiryUtc();

            googlePaid.setValidToUtc(newPremiumUntil);
            googlePaid.setLastVerifiedAtUtc(now);
            googlePaid.setLastGoogleVerifiedAtUtc(now);
            googlePaid.setUpdatedAtUtc(now);
            attemptTxService.saveEntitlement(googlePaid);

            attemptTxService.markGoogleDeferSuccess(
                    inProgress.getId(),
                    newPremiumUntil,
                    deferred.requestJson(),
                    deferred.rawResponseJson(),
                    deferred.httpStatus(),
                    now
            );

            return new RewardGrantResult(oldPremiumUntil, newPremiumUntil, now);
        } catch (GooglePlaySubscriptionDeferralService.DeferralException ex) {
            String grantStatus = ex.isRetryable()
                    ? GRANT_STATUS_FAILED_RETRYABLE
                    : GRANT_STATUS_FAILED_FINAL;

            String googleDeferStatus = ex.isRetryable()
                    ? "FAILED_RETRYABLE"
                    : "FAILED_FINAL";

            Instant nextRetryAt = ex.isRetryable()
                    ? now.plusSeconds(30L * 60L)
                    : null;

            attemptTxService.markGoogleDeferFailure(
                    inProgress.getId(),
                    grantStatus,
                    googleDeferStatus,
                    ex.getRequestJson(),
                    ex.getResponseJson(),
                    ex.getHttpStatus(),
                    ex.getErrorCode(),
                    truncate(ex.toString(), 500),
                    nextRetryAt,
                    now
            );

            if (ex.isRetryable()) {
                throw new RewardGrantDeferredException(ex.getErrorCode(), ex);
            }

            throw new RewardGrantFinalException(ex.getErrorCode(), "REWARD_GRANT_FAILED");
        } catch (RuntimeException ex) {
            attemptTxService.markGoogleDeferFailure(
                    inProgress.getId(),
                    GRANT_STATUS_FAILED_RETRYABLE,
                    "FAILED_RETRYABLE",
                    null,
                    null,
                    null,
                    "GOOGLE_PLAY_DEFER_FAILED",
                    truncate(ex.toString(), 500),
                    now.plusSeconds(30L * 60L),
                    now
            );

            throw new RewardGrantDeferredException("GOOGLE_PLAY_DEFER_FAILED", ex);
        }
    }

    private RewardGrantResult grantBackendOnlyReferralReward(
            Long inviterUserId,
            Long claimId,
            Instant now
    ) {
        List<UserEntitlementEntity> active = entitlementRepository.findActiveBestFirst(
                inviterUserId,
                now,
                PageRequest.of(0, 1)
        );

        UserEntitlementEntity current = active.isEmpty() ? null : active.get(0);
        Instant oldPremiumUntil = current == null ? null : current.getValidToUtc();
        Instant base = oldPremiumUntil != null && oldPremiumUntil.isAfter(now) ? oldPremiumUntil : now;
        Instant newPremiumUntil = base.plusSeconds(DAYS_ADDED * 24L * 3600L);

        UserEntitlementEntity reward = new UserEntitlementEntity();
        reward.setUserId(inviterUserId);
        reward.setEntitlementType("REFERRAL_REWARD");
        reward.setStatus("ACTIVE");
        reward.setValidFromUtc(now);
        reward.setValidToUtc(newPremiumUntil);
        reward.setSource("REFERRAL_REWARD");
        reward.setPaymentState("OK");
        reward.setLastVerifiedAtUtc(now);
        entitlementRepository.save(reward);

        saveAttemptLedger(
                inviterUserId,
                claimId,
                oldPremiumUntil,
                newPremiumUntil,
                null,
                GRANT_STATUS_SUCCESS,
                CHANNEL_BACKEND_ONLY,
                "NOT_REQUIRED",
                null,
                null,
                null,
                null,
                null,
                null,
                now
        );

        return new RewardGrantResult(oldPremiumUntil, newPremiumUntil, now);
    }

    private void enforceGoogleDeferRetryPolicy(
            Long inviterUserId,
            Long claimId,
            UserEntitlementEntity googlePaid,
            Instant now
    ) throws RewardGrantDeferredException, RewardGrantFinalException {
        MembershipRewardLedgerEntity latest = rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcDesc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId,
                        CHANNEL_GOOGLE_PLAY_DEFER
                )
                .orElse(null);

        if (latest == null) {
            return;
        }

        if (GRANT_STATUS_IN_PROGRESS.equals(latest.getGrantStatus())) {
            if (latest.getNextRetryAtUtc() != null && latest.getNextRetryAtUtc().isAfter(now)) {
                throw new RewardGrantDeferredException("GOOGLE_DEFER_IN_PROGRESS");
            }

            throw new RewardGrantDeferredException("GOOGLE_DEFER_RECONCILIATION_REQUIRED");
        }

        if (GRANT_STATUS_SUCCESS.equals(latest.getGrantStatus())) {
            return;
        }

        if (GRANT_STATUS_FAILED_FINAL.equals(latest.getGrantStatus())) {
            throw new RewardGrantFinalException(
                    latest.getErrorCode() == null ? "GOOGLE_DEFER_FAILED_FINAL" : latest.getErrorCode(),
                    "REWARD_GRANT_FAILED"
            );
        }

        if (!GRANT_STATUS_FAILED_RETRYABLE.equals(latest.getGrantStatus())) {
            return;
        }

        if (latest.getNextRetryAtUtc() != null && latest.getNextRetryAtUtc().isAfter(now)) {
            throw new RewardGrantDeferredException("GOOGLE_DEFER_WAITING_FOR_NEXT_RETRY");
        }

        long attempts = rewardLedgerRepository.countBySourceTypeAndSourceRefIdAndRewardChannel(
                SOURCE_TYPE_REFERRAL_SUCCESS,
                claimId,
                CHANNEL_GOOGLE_PLAY_DEFER
        );

        MembershipRewardLedgerEntity first = rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByGrantedAtUtcAsc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId,
                        CHANNEL_GOOGLE_PLAY_DEFER
                )
                .orElse(latest);

        boolean tooManyAttempts = attempts >= MAX_GOOGLE_DEFER_ATTEMPTS;
        boolean tooOld = first.getGrantedAtUtc() != null
                && first.getGrantedAtUtc().plusSeconds(GOOGLE_DEFER_FINAL_AFTER_SECONDS).isBefore(now);

        if (!tooManyAttempts && !tooOld) {
            return;
        }

        saveAttemptLedger(
                inviterUserId,
                claimId,
                googlePaid.getValidToUtc(),
                null,
                googlePaid.getPurchaseTokenHash(),
                GRANT_STATUS_FAILED_FINAL,
                CHANNEL_GOOGLE_PLAY_DEFER,
                "FAILED_FINAL",
                null,
                null,
                null,
                tooManyAttempts ? "GOOGLE_DEFER_MAX_RETRIES_EXCEEDED" : "GOOGLE_DEFER_RETRY_WINDOW_EXPIRED",
                tooManyAttempts
                        ? "Google Play defer exceeded max retry attempts."
                        : "Google Play defer retry window expired.",
                null,
                now
        );

        throw new RewardGrantFinalException(
                tooManyAttempts ? "GOOGLE_DEFER_MAX_RETRIES_EXCEEDED" : "GOOGLE_DEFER_RETRY_WINDOW_EXPIRED",
                "REWARD_GRANT_FAILED"
        );
    }

    private MembershipRewardLedgerEntity startGoogleDeferAttempt(
            Long inviterUserId,
            Long claimId,
            UserEntitlementEntity googlePaid,
            Instant now
    ) {
        Integer attemptNo = rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdAndRewardChannelOrderByAttemptNoDesc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId,
                        CHANNEL_GOOGLE_PLAY_DEFER
                )
                .map(MembershipRewardLedgerEntity::getAttemptNo)
                .map(it -> it + 1)
                .orElse(1);

        MembershipRewardLedgerEntity ledger = new MembershipRewardLedgerEntity();
        ledger.setUserId(inviterUserId);
        ledger.setSourceType(SOURCE_TYPE_REFERRAL_SUCCESS);
        ledger.setSourceRefId(claimId);
        ledger.setAttemptNo(attemptNo);
        ledger.setTraceId(UUID.randomUUID().toString());
        ledger.setGrantStatus(GRANT_STATUS_IN_PROGRESS);
        ledger.setRewardChannel(CHANNEL_GOOGLE_PLAY_DEFER);
        ledger.setGoogleDeferStatus("IN_PROGRESS");
        ledger.setGooglePurchaseTokenHash(googlePaid.getPurchaseTokenHash());
        ledger.setDaysAdded(DAYS_ADDED);
        ledger.setOldPremiumUntil(googlePaid.getValidToUtc());
        ledger.setNewPremiumUntil(null);
        ledger.setGrantedAtUtc(now);
        ledger.setNextRetryAtUtc(now.plusSeconds(GOOGLE_DEFER_IN_PROGRESS_TTL_SECONDS));
        ledger.setErrorCode(null);
        ledger.setErrorMessage("Google Play defer call started.");

        return attemptTxService.saveNewAttempt(ledger);
    }

    private Optional<RewardGrantResult> reconcileStaleGoogleDeferInProgress(
            Long inviterUserId,
            Long claimId,
            UserEntitlementEntity googlePaid,
            String rawPurchaseToken,
            GooglePlaySubscriptionDeferralService googleDeferral,
            Instant now
    ) throws RewardGrantDeferredException {
        MembershipRewardLedgerEntity latest = rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdAndRewardChannelAndGrantStatusOrderByGrantedAtUtcDesc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId,
                        CHANNEL_GOOGLE_PLAY_DEFER,
                        GRANT_STATUS_IN_PROGRESS
                )
                .orElse(null);

        if (latest == null) {
            return Optional.empty();
        }

        if (latest.getNextRetryAtUtc() != null && latest.getNextRetryAtUtc().isAfter(now)) {
            throw new RewardGrantDeferredException("GOOGLE_DEFER_IN_PROGRESS");
        }

        Instant oldPremiumUntil = latest.getOldPremiumUntil();
        if (oldPremiumUntil == null) {
            throw new RewardGrantDeferredException("GOOGLE_DEFER_RECONCILE_MISSING_OLD_EXPIRY");
        }

        Instant currentGoogleExpiry;
        try {
            currentGoogleExpiry = googleDeferral.getCurrentExpiry(rawPurchaseToken);
        } catch (RuntimeException ex) {
            throw new RewardGrantDeferredException("GOOGLE_DEFER_RECONCILE_GET_FAILED", ex);
        }

        Instant expectedMinimumExpiry =
                oldPremiumUntil.plusSeconds(GOOGLE_DEFER_RECONCILE_THRESHOLD_SECONDS);

        if (currentGoogleExpiry != null && !currentGoogleExpiry.isBefore(expectedMinimumExpiry)) {
            googlePaid.setValidToUtc(currentGoogleExpiry);
            googlePaid.setLastVerifiedAtUtc(now);
            googlePaid.setLastGoogleVerifiedAtUtc(now);
            googlePaid.setUpdatedAtUtc(now);
            attemptTxService.saveEntitlement(googlePaid);

            attemptTxService.markGoogleDeferSuccess(
                    latest.getId(),
                    currentGoogleExpiry,
                    latest.getGoogleDeferRequestJson(),
                    "{\"reconciled\":true,\"currentExpiry\":\"" + currentGoogleExpiry + "\"}",
                    200,
                    now
            );

            return Optional.of(new RewardGrantResult(
                    oldPremiumUntil,
                    currentGoogleExpiry,
                    now
            ));
        }

        attemptTxService.markGoogleDeferFailure(
                latest.getId(),
                GRANT_STATUS_FAILED_RETRYABLE,
                "FAILED_RETRYABLE",
                latest.getGoogleDeferRequestJson(),
                "{\"reconciled\":true,\"currentExpiry\":\"" + currentGoogleExpiry + "\"}",
                200,
                "GOOGLE_DEFER_NOT_APPLIED_AFTER_IN_PROGRESS",
                "Google Play defer in-progress attempt did not appear to update expiry.",
                now.plusSeconds(60L * 60L),
                now
        );

        throw new RewardGrantDeferredException("GOOGLE_DEFER_RECONCILED_NOT_APPLIED");
    }

    private MembershipRewardLedgerEntity saveAttemptLedger(
            Long inviterUserId,
            Long claimId,
            Instant oldPremiumUntil,
            Instant newPremiumUntil,
            String googlePurchaseTokenHash,
            String grantStatus,
            String rewardChannel,
            String googleDeferStatus,
            String googleDeferRequestJson,
            String googleDeferResponseJson,
            Integer googleDeferHttpStatus,
            String errorCode,
            String errorMessage,
            Instant nextRetryAtUtc,
            Instant now
    ) {
        MembershipRewardLedgerEntity ledger = new MembershipRewardLedgerEntity();
        ledger.setUserId(inviterUserId);
        ledger.setSourceType(SOURCE_TYPE_REFERRAL_SUCCESS);
        ledger.setSourceRefId(claimId);
        ledger.setAttemptNo(nextAttemptNo(claimId));
        ledger.setTraceId(UUID.randomUUID().toString());
        ledger.setGrantStatus(grantStatus);
        ledger.setRewardChannel(rewardChannel);
        ledger.setGooglePurchaseTokenHash(googlePurchaseTokenHash);
        ledger.setGoogleDeferStatus(googleDeferStatus);
        ledger.setGoogleDeferRequestJson(googleDeferRequestJson);
        ledger.setGoogleDeferResponseJson(googleDeferResponseJson);
        ledger.setGoogleDeferHttpStatus(googleDeferHttpStatus);
        ledger.setErrorCode(errorCode);
        ledger.setErrorMessage(truncate(errorMessage, 500));
        ledger.setDaysAdded(DAYS_ADDED);
        ledger.setOldPremiumUntil(oldPremiumUntil);
        ledger.setNewPremiumUntil(newPremiumUntil);
        ledger.setNextRetryAtUtc(nextRetryAtUtc);
        ledger.setGrantedAtUtc(now);
        return rewardLedgerRepository.save(ledger);
    }

    private Integer nextAttemptNo(Long claimId) {
        return rewardLedgerRepository
                .findTopBySourceTypeAndSourceRefIdOrderByAttemptNoDesc(
                        SOURCE_TYPE_REFERRAL_SUCCESS,
                        claimId
                )
                .map(it -> it.getAttemptNo() == null ? 1 : it.getAttemptNo() + 1)
                .orElse(1);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
