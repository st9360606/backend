package com.caloshape.backend.accountdelete.retention;

import com.caloshape.backend.testsupport.db.MySqlContainerBaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.retention.commercial.enabled=true")
class CommercialRetentionWorkerTest extends MySqlContainerBaseTest {

    @Autowired
    private CommercialRetentionWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runDaily_appliesThePublishedCommercialRetentionLimits() {
        Instant now = Instant.now();
        long deletedUserId = insertDeletedUser(yearsAgo(now, 4));

        insertEntitlement(UUID.randomUUID().toString(), 11L, "ciphertext-to-clear", now.minus(181, ChronoUnit.DAYS));
        insertEntitlement(UUID.randomUUID().toString(), 12L, "ciphertext-to-delete", yearsAgo(now, 6));
        insertRewardLedger(yearsAgo(now, 6));
        insertReferralClaim(yearsAgo(now, 6));
        insertRiskSignal("ALLOW", monthsAgo(now, 25));
        insertRiskSignal("DENY", yearsAgo(now, 4));
        insertRiskSignal("DENY", yearsAgo(now, 6));
        insertCompletedDeletionRequest(deletedUserId, yearsAgo(now, 4));

        worker.runDaily();

        assertThat(stringValue("SELECT purchase_token_ciphertext FROM user_entitlements WHERE user_id=11"))
                .isNull();
        assertThat(count("SELECT COUNT(*) FROM user_entitlements WHERE user_id=11")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_entitlements WHERE user_id=12")).isZero();
        assertThat(count("SELECT COUNT(*) FROM membership_reward_ledger")).isZero();
        assertThat(count("SELECT COUNT(*) FROM referral_claims")).isZero();
        assertThat(count("SELECT COUNT(*) FROM referral_risk_signals WHERE decision='ALLOW'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM referral_risk_signals WHERE decision='DENY'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account_deletion_requests")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE id=?", deletedUserId)).isZero();
    }

    private long insertDeletedUser(Instant deletedAt) {
        jdbc.update("""
                INSERT INTO users (provider, status, deleted_at_utc, created_at, updated_at)
                VALUES ('EMAIL', 'DELETED', ?, ?, ?)
                """, deletedAt, deletedAt, deletedAt);
        return jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
    }

    private void insertEntitlement(String id, long userId, String ciphertext, Instant endedAt) {
        jdbc.update("""
                INSERT INTO user_entitlements (
                    id, user_id, entitlement_type, status, valid_from_utc, valid_to_utc,
                    purchase_token_ciphertext, source, created_at_utc, updated_at_utc
                ) VALUES (?, ?, 'MONTHLY', 'EXPIRED', ?, ?, ?, 'GOOGLE_PLAY', ?, ?)
                """, id, userId, endedAt.minus(30, ChronoUnit.DAYS), endedAt, ciphertext, endedAt, endedAt);
    }

    private void insertRewardLedger(Instant grantedAt) {
        jdbc.update("""
                INSERT INTO membership_reward_ledger (
                    user_id, source_type, source_ref_id, attempt_no, grant_status, days_added, granted_at_utc
                ) VALUES (20, 'REFERRAL_SUCCESS', 30, 1, 'SUCCESS', 30, ?)
                """, grantedAt);
    }

    private void insertReferralClaim(Instant updatedAt) {
        jdbc.update("""
                INSERT INTO referral_claims (
                    inviter_user_id, invitee_user_id, promo_code, status, reject_reason, created_at_utc, updated_at_utc
                ) VALUES (40, 41, 'RETENTION', 'REJECTED', 'NONE', ?, ?)
                """, updatedAt, updatedAt);
    }

    private void insertRiskSignal(String decision, Instant createdAt) {
        jdbc.update("""
                INSERT INTO referral_risk_signals (claim_id, risk_score, decision, created_at_utc)
                VALUES (999, 0, ?, ?)
                """, decision, createdAt);
    }

    private void insertCompletedDeletionRequest(long userId, Instant completedAt) {
        jdbc.update("""
                INSERT INTO account_deletion_requests (
                    id, user_id, req_status, requested_at_utc, completed_at_utc,
                    subscription_warning_acknowledged, user_requested_google_play_cancel,
                    has_active_google_play_subscription_at_request, attempts
                ) VALUES (?, ?, 'DONE', ?, ?, false, false, false, 1)
                """, UUID.randomUUID().toString(), userId, completedAt, completedAt);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String stringValue(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private Instant yearsAgo(Instant now, int years) {
        return now.atZone(ZoneOffset.UTC).minusYears(years).toInstant();
    }

    private Instant monthsAgo(Instant now, int months) {
        return now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
    }
}
