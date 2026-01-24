package com.calai.backend.accountdelete;

import com.calai.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.calai.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.calai.backend.accountdelete.service.AccountDeletionService;
import com.calai.backend.accountdelete.worker.AccountDeletionWorker;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
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

    @Test
    void worker_should_purge_and_finish_after_jobs_succeeded() throws Exception {
        // given user
        User u = new User();
        u.setEmail("kurt@example.com");
        u.setProvider(com.calai.backend.auth.entity.AuthProvider.EMAIL);
        u.setStatus("ACTIVE");
        userRepo.saveAndFlush(u);

        Long userId = u.getId();

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
        deletionService.requestDeletion(userId);

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

        AccountDeletionRequestEntity req = reqRepo.findByUserId(userId).orElseThrow();
        assertThat(req.getReqStatus()).isEqualTo("DONE");

        User u2 = userRepo.findById(userId).orElseThrow();
        assertThat(u2.getStatus()).isEqualTo("DELETED");
        assertThat(u2.getEmail()).isNull(); // allow re-register
    }
}
