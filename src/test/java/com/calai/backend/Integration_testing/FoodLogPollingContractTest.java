package com.calai.backend.Integration_testing;


import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestAuthConfig.class)
public class FoodLogPollingContractTest {

    @Autowired FoodLogService service;
    @Autowired FoodLogRepository logRepo;
    @Autowired FoodLogTaskRepository taskRepo;

    @Test
    void failed_should_return_retryAfter_and_pollAfter_clamped() {
        Long userId = 1L;
        ZoneId tz = ZoneId.of("Asia/Taipei");
        Instant now = Instant.now();

        // 建立一筆 FAILED log
        FoodLogEntity log = new FoodLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(userId);

        log.setStatus(FoodLogStatus.FAILED);
        log.setMethod("PHOTO");
        log.setProvider("STUB");
        log.setDegradeLevel("DG-0");

        log.setCapturedAtUtc(now);
        log.setCapturedTz(tz.getId());
        log.setCapturedLocalDate(LocalDate.now(tz));
        log.setServerReceivedAtUtc(now);

        log.setTimeSource(TimeSource.SERVER_RECEIVED);
        log.setTimeSuspect(false);

        log.setLastErrorCode("PROVIDER_FAILED");
        log.setLastErrorMessage("simulated failure");
        logRepo.save(log);

        // 建立對應 task：nextRetry = now + 120s（>60 應該被 clamp）
        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        task.setPollAfterSec(2);
        task.setAttempts(3);
        task.setNextRetryAtUtc(now.plusSeconds(120));
        taskRepo.save(task);

        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");

        assertThat(env.status()).isEqualTo("FAILED");
        assertThat(env.task()).isNotNull();
        assertThat(env.error()).isNotNull();

        // ✅ pollAfterSec：FAILED 時 clamp 到 60
        assertThat(env.task().pollAfterSec()).isEqualTo(60);

        // ✅ retryAfterSec：允許些微時間抖動（呼叫到這裡可能已過 0~2 秒）
        assertThat(env.error().retryAfterSec()).isNotNull();
        assertThat(env.error().retryAfterSec()).isBetween(110, 120);
    }

    @Test
    void failed_with_past_nextRetry_should_return_retryAfter_0() {
        Long userId = 1L;
        ZoneId tz = ZoneId.of("Asia/Taipei");
        Instant now = Instant.now();

        FoodLogEntity log = new FoodLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(userId);
        log.setStatus(FoodLogStatus.FAILED);
        log.setMethod("PHOTO");
        log.setProvider("STUB");
        log.setDegradeLevel("DG-0");
        log.setCapturedAtUtc(now);
        log.setCapturedTz(tz.getId());
        log.setCapturedLocalDate(LocalDate.now(tz));
        log.setServerReceivedAtUtc(now);
        log.setTimeSource(TimeSource.SERVER_RECEIVED);
        log.setTimeSuspect(false);
        log.setLastErrorCode("PROVIDER_FAILED");
        logRepo.save(log);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        task.setNextRetryAtUtc(now.minusSeconds(10)); // 過期
        taskRepo.save(task);

        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");

        assertThat(env.status()).isEqualTo("FAILED");
        assertThat(env.error()).isNotNull();
        assertThat(env.error().retryAfterSec()).isEqualTo(0);

        // pollAfterSec：你的邏輯會把 0 clamp 到 2
        assertThat(env.task()).isNotNull();
        assertThat(env.task().pollAfterSec()).isEqualTo(2);
    }

    @Test
    void pending_queued_should_backoff_by_queue_age() {
        Long userId = 1L;
        ZoneId tz = ZoneId.of("Asia/Taipei");
        Instant now = Instant.now();

        FoodLogEntity log = new FoodLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(userId);
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setProvider("STUB");
        log.setDegradeLevel("DG-0");
        log.setCapturedAtUtc(now);
        log.setCapturedTz(tz.getId());
        log.setCapturedLocalDate(LocalDate.now(tz));
        log.setServerReceivedAtUtc(now);
        log.setTimeSource(TimeSource.SERVER_RECEIVED);
        log.setTimeSuspect(false);
        logRepo.save(log);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setPollAfterSec(2);

        // ✅ 模擬排隊 70 秒
        task.setCreatedAtUtc(now.minusSeconds(70));
        task.setUpdatedAtUtc(now.minusSeconds(10));
        taskRepo.save(task);

        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");
        assertThat(env.status()).isEqualTo("PENDING");
        assertThat(env.task()).isNotNull();
        assertThat(env.task().pollAfterSec()).isEqualTo(8);
    }

}

