package com.calai.backend.Integration_testing;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.Integration_testing.config.TestProviderFailConfig;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ✅ 驗證 ProviderErrorMapper 的 mappedCode 會落到 task/log，且 API getOne 會回一致的 errorCode。
 */
@SpringBootTest
@Import({TestAuthConfig.class, TestProviderFailConfig.class})
public class FoodLogProviderErrorMappingTest {

    @Autowired FoodLogService service;
    @Autowired FoodLogTaskWorker worker;
    @Autowired FoodLogRepository logRepo;
    @Autowired FoodLogTaskRepository taskRepo;

    @Test
    void timeout_should_map_to_PROVIDER_TIMEOUT_and_surface_in_getOne() throws Exception {
        Long userId = 1L;
        Instant now = Instant.now();

        // 1) 先用你現有 service 建一筆「可被 worker 撿到」的 PENDING task
        //    最簡單：直接建 log + task（避免走 upload/Storage）
        FoodLogEntity log = new FoodLogEntity();
        log.setUserId(userId);
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setProvider("STUB");
        log.setDegradeLevel("DG-0");
        log.setCapturedAtUtc(now);
        log.setCapturedTz("Asia/Taipei");
        log.setCapturedLocalDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")));
        log.setServerReceivedAtUtc(now);
        log.setTimeSource(TimeSource.SERVER_RECEIVED); // ✅ time_source NOT NULL
        log.setTimeSuspect(false);                    // ✅ time_suspect NOT NULL（你 DB 有 default，但 JPA insert 仍會帶值）

        // ✅ 讓 worker 不會因為缺 imageObjectKey 直接取消
        log.setImageObjectKey("user-1/blobs/sha256/dummy.jpg");
        logRepo.save(log);

        var task = new com.calai.backend.foodlog.entity.FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(com.calai.backend.foodlog.entity.FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setPollAfterSec(2);
        taskRepo.save(task);

        // 2) 執行 worker：因為 providerClient 永遠 timeout → 應該把 task/log 標成 FAILED + code=PROVIDER_TIMEOUT
        worker.runOnce();

        var updatedLog = logRepo.findById(log.getId()).orElseThrow();
        assertThat(updatedLog.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(updatedLog.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");

        var updatedTask = taskRepo.findByFoodLogId(log.getId()).orElseThrow();
        assertThat(updatedTask.getTaskStatus()).isEqualTo(com.calai.backend.foodlog.entity.FoodLogTaskEntity.TaskStatus.FAILED);
        assertThat(updatedTask.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");

        // 3) GET one：Envelope 應該把 errorCode 帶出來
        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");
        assertThat(env.status()).isEqualTo("FAILED");
        assertThat(env.error()).isNotNull();
        assertThat(env.error().errorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(env.error().retryAfterSec()).isNotNull();
    }
}
