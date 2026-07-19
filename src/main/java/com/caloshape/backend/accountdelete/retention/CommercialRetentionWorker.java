package com.caloshape.backend.accountdelete.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Deletes or minimizes the commercial records that are exceptionally retained
 * after account deletion. This worker intentionally never processes active
 * subscriptions or retryable referral rewards.
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(
        prefix = "app.retention.commercial",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class CommercialRetentionWorker {

    private final CommercialRetentionProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(cron = "0 10 5 * * *", zone = "UTC")
    @Transactional
    public void runDaily() {
        if (!properties.isEnabled()) {
            return;
        }

        Instant now = Instant.now(clock);
        int batchSize = properties.getBatchSize();
        Instant tokenCutoff = now.minus(properties.getEncryptedPurchaseTokenRetention());
        Instant billingCutoff = yearsAgo(now, properties.getBillingAuditRetentionYears());
        Instant referralCutoff = yearsAgo(now, properties.getReferralRewardRetentionYears());
        Instant standardRiskCutoff = monthsAgo(now, properties.getStandardRiskRetentionMonths());
        Instant deniedRiskCutoff = yearsAgo(now, properties.getDeniedRiskRetentionYears());
        Instant deletionRequestCutoff = yearsAgo(now, properties.getDeletionRequestRetentionYears());

        int ciphertextCleared = jdbc.update("""
                UPDATE user_entitlements
                   SET purchase_token_ciphertext=NULL
                 WHERE source='GOOGLE_PLAY'
                   AND purchase_token_ciphertext IS NOT NULL
                   AND status IN ('EXPIRED', 'CANCELLED', 'REVOKED')
                   AND COALESCE(revoked_at_utc, valid_to_utc, updated_at_utc) < ?
                 LIMIT ?
                """, tokenCutoff, batchSize);

        int expiredEntitlementsDeleted = jdbc.update("""
                DELETE FROM user_entitlements
                 WHERE status IN ('EXPIRED', 'CANCELLED', 'REVOKED')
                   AND COALESCE(revoked_at_utc, valid_to_utc, updated_at_utc) < ?
                 LIMIT ?
                """, billingCutoff, batchSize);

        int settledRewardLedgersDeleted = jdbc.update("""
                DELETE FROM membership_reward_ledger
                 WHERE grant_status IN ('SUCCESS', 'FAILED_FINAL')
                   AND granted_at_utc < ?
                 LIMIT ?
                """, referralCutoff, batchSize);

        int expiredReferralClaimsDeleted = jdbc.update("""
                DELETE FROM referral_claims
                 WHERE status IN ('SUCCESS', 'REJECTED', 'EXPIRED')
                   AND updated_at_utc < ?
                 LIMIT ?
                """, referralCutoff, batchSize);

        int routineRiskSignalsDeleted = jdbc.update("""
                DELETE FROM referral_risk_signals
                 WHERE decision <> 'DENY'
                   AND created_at_utc < ?
                 LIMIT ?
                """, standardRiskCutoff, batchSize);

        int deniedRiskSignalsDeleted = jdbc.update("""
                DELETE FROM referral_risk_signals
                 WHERE decision = 'DENY'
                   AND created_at_utc < ?
                 LIMIT ?
                """, deniedRiskCutoff, batchSize);

        int completedDeletionRequestsDeleted = jdbc.update("""
                DELETE FROM account_deletion_requests
                 WHERE req_status='DONE'
                   AND completed_at_utc < ?
                 LIMIT ?
                """, deletionRequestCutoff, batchSize);

        int deletedUsersPurged = jdbc.update("""
                DELETE FROM users
                 WHERE status='DELETED'
                   AND deleted_at_utc < ?
                 LIMIT ?
                """, deletionRequestCutoff, batchSize);

        if (ciphertextCleared + expiredEntitlementsDeleted + settledRewardLedgersDeleted
                + expiredReferralClaimsDeleted + routineRiskSignalsDeleted + deniedRiskSignalsDeleted
                + completedDeletionRequestsDeleted + deletedUsersPurged > 0) {
            log.info(
                    "commercial retention done. ciphertextCleared={} entitlementsDeleted={} rewardLedgersDeleted={} referralClaimsDeleted={} routineRiskSignalsDeleted={} deniedRiskSignalsDeleted={} deletionRequestsDeleted={} deletedUsersPurged={}",
                    ciphertextCleared,
                    expiredEntitlementsDeleted,
                    settledRewardLedgersDeleted,
                    expiredReferralClaimsDeleted,
                    routineRiskSignalsDeleted,
                    deniedRiskSignalsDeleted,
                    completedDeletionRequestsDeleted,
                    deletedUsersPurged
            );
        }
    }

    private Instant yearsAgo(Instant now, int years) {
        return now.atZone(ZoneOffset.UTC).minusYears(years).toInstant();
    }

    private Instant monthsAgo(Instant now, int months) {
        return now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
    }
}
