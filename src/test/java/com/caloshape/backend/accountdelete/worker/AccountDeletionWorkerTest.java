package com.caloshape.backend.accountdelete.worker;

import com.caloshape.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.caloshape.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.caloshape.backend.accountdelete.service.AccountDeletionService;
import com.caloshape.backend.accountdelete.service.AccountDeletionPseudonymizer;
import com.caloshape.backend.foodlog.model.FoodLogStatus;
import com.caloshape.backend.foodlog.model.TimeSource;
import com.caloshape.backend.foodlog.entity.FoodLogEntity;
import com.caloshape.backend.foodlog.repo.DeletionJobRepository;
import com.caloshape.backend.foodlog.repo.FoodLogRepository;
import com.caloshape.backend.testsupport.db.MySqlContainerBaseTest;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.users.user.repo.UserRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class AccountDeletionWorkerTest extends MySqlContainerBaseTest {

    @Autowired
    AccountDeletionService deletionService;
    @Autowired
    AccountDeletionWorker worker;

    @Autowired
    UserRepo userRepo;
    @Autowired
    AccountDeletionRequestRepository reqRepo;

    @Autowired
    FoodLogRepository foodLogRepo;
    @Autowired
    DeletionJobRepository deletionJobRepo;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper om;
    @Autowired
    AccountDeletionPseudonymizer pseudonymizer;

    @Test
    void worker_should_purge_and_finish_after_jobs_succeeded() throws Exception {
        // given user
        User u = new User();
        u.setEmail("kurt@example.com");
        u.setProvider(com.caloshape.backend.auth.entity.AuthProvider.EMAIL);
        u.setStatus("ACTIVE");
        userRepo.saveAndFlush(u);

        Long userId = u.getId();
        seedUserOwnedDataset(userId);
        seedRetainedCommercialDataset(userId);

        // given one food log with effective + image refs
        FoodLogEntity f = new FoodLogEntity();
        f.setUserId(userId);
        f.setStatus(FoodLogStatus.SAVED);
        f.setMethod("ALBUM");
        f.setProvider("GEMINI");
        f.setDegradeLevel("DG-0");

        Instant now = Instant.now();
        f.setServerReceivedAtUtc(now.minusSeconds(10));
        f.setCapturedAtUtc(now.minusSeconds(10));
        f.setCapturedTz("Asia/Taipei");
        f.setCapturedLocalDate(LocalDate.ofInstant(now, ZoneId.of("Asia/Taipei")));
        f.setTimeSource(TimeSource.SERVER_RECEIVED);
        f.setTimeSuspect(false);

        f.setImageSha256("a".repeat(64));
        f.setImageObjectKey("user-" + userId + "/blobs/sha256/" + f.getImageSha256() + ".jpg");
        f.setImageContentType("image/jpeg");
        f.setImageSizeBytes(100L);

        f.setEffective(om.readTree("{\"foodName\":\"Toast\",\"nutrients\":{\"kcal\":75}}"));
        foodLogRepo.saveAndFlush(f);

        // when: request deletion (sync phase)
        // 此測試使用者沒有 active Google Play subscription，所以不需要 subscription warning ack。
        String requestId = deletionService.requestDeletion(
                userId,
                false,
                false
        ).getId();

        // first run: should enqueue deletion job + soft delete food log
        worker.runOnce();

        FoodLogEntity after1 = foodLogRepo.findById(f.getId()).orElseThrow();
        assertThat(after1.getStatus()).isEqualTo(FoodLogStatus.DELETED);
        assertThat(after1.getEffective()).isNull();
        assertThat(deletionJobRepo.findByFoodLogId(f.getId())).isPresent();

        // simulate DeletionJobWorker completed jobs
        int updJobs = jdbc.update("UPDATE deletion_jobs SET job_status='SUCCEEDED' WHERE user_id=?", userId);
        assertThat(updJobs).isGreaterThan(0); // ✅ 至少要改到 1 筆

        Long left = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM deletion_jobs
                     WHERE user_id=?
                       AND job_status IN ('QUEUED','RUNNING','FAILED')
                """, Long.class, userId);

        assertThat(left).isEqualTo(0L);

        // simulate blobs all gone
        jdbc.update("DELETE FROM image_blobs WHERE user_id=?", userId);

        // ✅ 關鍵：讓 RUNNING 任務立刻可被 claim（status + next_retry <= now）
        int updReq = jdbc.update("""
                    UPDATE account_deletion_requests
                       SET req_status='RUNNING',
                           next_retry_at_utc = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)
                     WHERE user_id=?
                """, userId);


        assertThat(updReq).isEqualTo(1); // ✅ 必須改到 1 筆

        // ✅ 再加一個硬驗收：此刻 outstanding 應該已經是 0
        long out = deletionJobRepo.countOutstandingByUserId(userId);
        assertThat(out).isEqualTo(0L);

        var row = jdbc.queryForMap("""
                    SELECT req_status, next_retry_at_utc
                      FROM account_deletion_requests
                     WHERE user_id=?
                """, userId);

        assertThat(row.get("req_status").toString()).isEqualTo("RUNNING");
        assertThat(row.get("next_retry_at_utc")).isNotNull();

        Object v = row.get("next_retry_at_utc");
        Instant nra;
        if (v instanceof java.sql.Timestamp ts) {
            nra = ts.toInstant();
        } else if (v instanceof java.time.LocalDateTime ldt) {
            nra = ldt.toInstant(java.time.ZoneOffset.UTC);
        } else {
            throw new AssertionError("unexpected type for next_retry_at_utc: " + (v == null ? "null" : v.getClass()));
        }
        assertThat(nra).isBefore(Instant.now().plusSeconds(1));


        // second run: should finalize
        worker.runOnce();

        assertThat(deletionJobRepo.countOutstandingByUserId(userId)).isEqualTo(0);
        assertThat(foodLogRepo.countByUserId(userId)).isEqualTo(0);

        // ✅ 加一個驗收：deletion_jobs 最後也應該被清掉（避免殘留/FK）
        Long dj = jdbc.queryForObject("SELECT COUNT(*) FROM deletion_jobs WHERE user_id=?", Long.class, userId);
        assertThat(dj).isEqualTo(0L);

        AccountDeletionRequestEntity req = reqRepo.findById(requestId).orElseThrow();
        assertThat(req.getReqStatus()).isEqualTo("DONE");
        assertThat(req.getUserId()).isNegative();

        long pseudonymousUserId = pseudonymizer.userId(userId);
        assertThat(req.getUserId()).isEqualTo(pseudonymousUserId);
        assertPurgedUserOwnedDataset(userId);
        assertRetainedCommercialDatasetWasPseudonymized(userId, pseudonymousUserId);

        User u2 = userRepo.findById(userId).orElseThrow();
        assertThat(u2.getStatus()).isEqualTo("DELETED");
        assertThat(u2.getEmail()).isNull(); // allow re-register
    }

    private void seedUserOwnedDataset(Long userId) {
        jdbc.update("""
                INSERT INTO food_log_requests (user_id, request_id, status, created_at_utc, updated_at_utc)
                VALUES (?, 'delete-it-request', 'RESERVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO usage_counters (user_id, local_date, used_count, updated_at_utc)
                VALUES (?, CURRENT_DATE, 1, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_daily_activity (
                    user_id, local_date, timezone, day_start_utc, day_end_utc, ingest_source, updated_at
                ) VALUES (?, CURRENT_DATE, 'UTC', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'MANUAL', UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_daily_nutrition_summary (
                    user_id, local_date, timezone, total_kcal, total_protein_g, total_carbs_g, total_fats_g,
                    total_fiber_g, total_sugar_g, total_sodium_mg, avg_health_score, meal_count,
                    last_recomputed_at_utc, created_at_utc, updated_at_utc
                ) VALUES (?, CURRENT_DATE, 'UTC', 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_ai_quota_state (
                    user_id, daily_key, daily_count, monthly_key, monthly_count, cooldown_strikes, updated_at_utc
                ) VALUES (?, '2026-07-17@UTC', 1, '2026-07@UTC', 1, 0, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_notifications (
                    user_id, type, title, message, source_type, source_ref_id, is_read, created_at_utc
                ) VALUES (?, 'SYSTEM', 'Delete test', 'Delete test', 'TEST', 1, false, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO email_outbox (
                    user_id, to_email, template_type, template_payload_json, dedupe_key, retry_count, status, created_at_utc
                ) VALUES (?, 'delete-it@example.com', 'TEST', JSON_OBJECT('test', true), 'delete-it-outbox', 0, 'PENDING', UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_referral_codes (user_id, promo_code, is_active, created_at_utc, updated_at_utc)
                VALUES (?, 'DELETEIT', true, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_water_daily (user_id, local_date, cups, ml, fl_oz, updated_at)
                VALUES (?, CURRENT_DATE, 1, 237, 8, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO fasting_plan (user_id, plan_code, start_time, end_time, enabled, time_zone, created_at, updated_at)
                VALUES (?, '16:8', '08:00', '16:00', false, 'UTC', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO workout_alias_event (user_id, lang_tag, phrase_lower, used_generic, created_at)
                VALUES (?, 'en', 'delete test workout', false, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_daily_workout_summary (
                    user_id, local_date, timezone, workout_kcal, activity_kcal, total_burned_kcal,
                    workout_session_count, last_recomputed_at_utc, created_at_utc, updated_at_utc
                ) VALUES (?, CURRENT_DATE, 'UTC', 0, 0, 0, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO weight_history (user_id, log_date, weight_kg, weight_lbs, timezone, created_at, updated_at)
                VALUES (?, CURRENT_DATE, 70.0, 154.3, 'UTC', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO weight_timeseries (user_id, log_date, weight_kg, weight_lbs, timezone, created_at)
                VALUES (?, CURRENT_DATE, 70.0, 154.3, 'UTC', UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO user_profiles (
                    user_id, daily_step_goal, unit_preference, daily_workout_goal_kcal, kcal, carbs_g, protein_g,
                    fat_g, fiber_g, sugar_g, sodium_mg, water_ml, water_mode, bmi, bmi_class, plan_mode,
                    calc_version, created_at, updated_at
                ) VALUES (?, 10000, 'KG', 450, 0, 0, 0, 0, 35, 0, 2300, 0,
                    'AUTO', 0, 'UNKNOWN', 'AUTO', 'healthcalc_v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO auth_tokens (token, user_id, type, expires_at, created_at, revoked)
                VALUES (?, ?, 'ACCESS', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), UTC_TIMESTAMP(6), false)
                """, "b".repeat(64), userId);
        jdbc.update("""
                INSERT INTO image_blobs (
                    user_id, sha256, object_key, content_type, size_bytes, ext, ref_count, created_at_utc, updated_at_utc
                ) VALUES (?, ?, 'delete-it.jpg', 'image/jpeg', 1, '.jpg', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, "c".repeat(64));
    }

    private void seedRetainedCommercialDataset(Long userId) {
        jdbc.update("""
                INSERT INTO user_entitlements (
                    id, user_id, entitlement_type, status, valid_from_utc, valid_to_utc, source,
                    created_at_utc, updated_at_utc
                ) VALUES (
                    '00000000-0000-0000-0000-000000000101', ?, 'YEARLY', 'EXPIRED',
                    DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 YEAR),
                    DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 YEAR), 'GOOGLE_PLAY',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                )
                """, userId);
        jdbc.update("""
                INSERT INTO referral_claims (
                    inviter_user_id, invitee_user_id, promo_code, status, reject_reason, created_at_utc, updated_at_utc
                ) VALUES (?, ?, 'DELETEIT', 'SUCCESS', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, userId);
        jdbc.update("""
                INSERT INTO membership_reward_ledger (
                    user_id, source_type, source_ref_id, attempt_no, grant_status, days_added,
                    granted_at_utc, google_defer_request_json, google_defer_response_json, error_message
                ) VALUES (?, 'REFERRAL', 1, 1, 'SUCCESS', 30, UTC_TIMESTAMP(6), '{"request":true}', '{"response":true}', 'test')
                """, userId);
        jdbc.update("""
                INSERT INTO referral_case_snapshot (
                    inviter_user_id, total_invited, success_count, rejected_count, pending_verification_count,
                    total_rewarded_days, updated_at_utc
                ) VALUES (?, 1, 1, 0, 0, 30, UTC_TIMESTAMP(6))
                """, userId);
        jdbc.update("""
                INSERT INTO entitlement_transfer_audit (
                    purchase_token_hash, old_user_id, new_user_id, reason, transferred_at_utc
                ) VALUES (?, ?, ?, 'TEST', UTC_TIMESTAMP(6))
                """, "d".repeat(64), userId, userId);
    }

    private void assertPurgedUserOwnedDataset(Long userId) {
        for (String table : java.util.List.of(
                "food_log_requests",
                "usage_counters",
                "user_daily_activity",
                "user_daily_nutrition_summary",
                "user_ai_quota_state",
                "user_notifications",
                "email_outbox",
                "user_referral_codes",
                "user_water_daily",
                "fasting_plan",
                "workout_alias_event",
                "user_daily_workout_summary",
                "weight_history",
                "weight_timeseries",
                "user_profiles",
                "auth_tokens",
                "image_blobs"
        )) {
            assertThat(countRows(table, "user_id", userId)).isZero();
        }
    }

    private void assertRetainedCommercialDatasetWasPseudonymized(Long userId, long pseudonymousUserId) {
        assertThat(countRows("user_entitlements", "user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("user_entitlements", "user_id", userId)).isZero();
        assertThat(countRows("membership_reward_ledger", "user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("membership_reward_ledger", "user_id", userId)).isZero();
        assertThat(countRows("referral_case_snapshot", "inviter_user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("entitlement_transfer_audit", "old_user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("entitlement_transfer_audit", "new_user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("referral_claims", "inviter_user_id", pseudonymousUserId)).isEqualTo(1);
        assertThat(countRows("referral_claims", "invitee_user_id", pseudonymousUserId)).isEqualTo(1);

        var rewardRow = jdbc.queryForMap("""
                SELECT google_defer_request_json, google_defer_response_json, error_message
                  FROM membership_reward_ledger
                 WHERE user_id=?
                """, pseudonymousUserId);
        assertThat(rewardRow.get("google_defer_request_json")).isNull();
        assertThat(rewardRow.get("google_defer_response_json")).isNull();
        assertThat(rewardRow.get("error_message")).isNull();
    }

    private long countRows(String table, String userColumn, long userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + userColumn + "=?",
                Long.class,
                userId
        );
        return count == null ? 0 : count;
    }
}
