package com.calai.backend.gemini;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.retention.FoodLogRetentionWorker;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.gemini.testsupport.MySqlContainerBaseTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class FoodLogRetentionWorkerTest extends MySqlContainerBaseTest {

    @Autowired FoodLogRepository foodLogRepo;
    @Autowired DeletionJobRepository deletionJobRepo;
    @Autowired FoodLogRetentionWorker retentionWorker;
    @Autowired ObjectMapper om;

    @DynamicPropertySource
    static void retentionProps(DynamicPropertyRegistry r) {
        // ✅ 測試時確保 retention 開啟
        r.add("app.retention.foodlog.enabled", () -> "true");
        r.add("app.retention.foodlog.keep-draft", () -> "PT72H");   // 3 days
        r.add("app.retention.foodlog.keep-saved", () -> "PT768H");  // 32 days
        r.add("app.retention.foodlog.batch-size", () -> "50");
    }

    @Test
    void retention_should_delete_expired_draft_and_enqueue_deletion_job() throws Exception {
        // given：一筆超過 3 天的 DRAFT
        Long userId = 1L;
        ZoneId tz = ZoneId.of("Asia/Taipei");

        Instant now = Instant.now();
        Instant receivedAt = now.minusSeconds(4L * 24 * 3600); // 4 days ago

        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setStatus(FoodLogStatus.DRAFT);
        e.setMethod("ALBUM");
        e.setProvider("GEMINI");
        e.setDegradeLevel("DG-0");

        // 時間欄位（Retention 用 serverReceivedAtUtc）
        e.setServerReceivedAtUtc(receivedAt);
        e.setCapturedAtUtc(receivedAt);
        e.setCapturedTz(tz.getId());
        e.setCapturedLocalDate(LocalDate.ofInstant(receivedAt, tz));

        e.setTimeSource(TimeSource.SERVER_RECEIVED);
        e.setTimeSuspect(false);

        // 圖片 refs（Retention 會 enqueue deletion job）
        e.setImageSha256("a".repeat(64));
        e.setImageObjectKey("user-1/blobs/sha256/" + e.getImageSha256() + ".jpg");
        e.setImageContentType("image/jpeg");
        e.setImageSizeBytes(123L);

        // effective 有值（Retention 要清成 null）
        e.setEffective(om.readTree("""
          {
            "foodName":"Toast",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
            "confidence":0.9
          }
        """));

        foodLogRepo.saveAndFlush(e);

        String foodLogId = e.getId();
        assertThat(foodLogId).isNotBlank();

        // when：跑 retention
        retentionWorker.runDaily();

        // then：log 被軟刪 + effective 清空
        FoodLogEntity after = foodLogRepo.findById(foodLogId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(FoodLogStatus.DELETED);
        assertThat(after.getDeletedBy()).isEqualTo("RETENTION");
        assertThat(after.getDeletedAtUtc()).isNotNull();
        assertThat(after.getEffective()).isNull();

        // 並且 enqueue deletion job（QUEUED）
        DeletionJobEntity job = deletionJobRepo.findByFoodLogId(foodLogId).orElseThrow();
        assertThat(job.getJobStatus()).isEqualTo(DeletionJobEntity.JobStatus.QUEUED);
        assertThat(job.getUserId()).isEqualTo(userId);
        assertThat(job.getSha256()).isEqualTo("a".repeat(64));
        assertThat(job.getImageObjectKey()).isEqualTo(after.getImageObjectKey());
    }
}
