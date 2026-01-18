package com.calai.backend.Integration_testing;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.calai.backend.foodlog.task.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ✅ 驗證 ProviderErrorMapper 的 mappedCode 會落到 task/log，且 API getOne 會回一致的 errorCode。
 */
@SpringBootTest(properties = {
        // ✅ 保險：即使 worker 走 pick() fallback，也會選到 GEMINI
        "app.foodlog.provider=GEMINI"
})
@Import({
        TestAuthConfig.class,
        FoodLogProviderErrorMappingTest.TimeoutGeminiProviderConfig.class
})
public class FoodLogProviderErrorMappingTest {

    @Autowired FoodLogService service;
    @Autowired FoodLogTaskWorker worker;
    @Autowired FoodLogRepository logRepo;
    @Autowired FoodLogTaskRepository taskRepo;

    @Test
    void timeout_should_map_to_PROVIDER_TIMEOUT_and_surface_in_getOne() throws Exception {
        Long userId = 1L;
        Instant now = Instant.now();

        // 1) 直接建 log（避免走 upload/Storage）
        FoodLogEntity log = new FoodLogEntity();
        log.setUserId(userId);
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");

        // ✅ 關鍵：指定 provider=GEMINI，worker 走 pickStrict() 也不會炸
        log.setProvider("GEMINI");

        log.setDegradeLevel("DG-0");
        log.setCapturedAtUtc(now);
        log.setCapturedTz("Asia/Taipei");
        log.setCapturedLocalDate(LocalDate.now(ZoneId.of("Asia/Taipei")));
        log.setServerReceivedAtUtc(now);
        log.setTimeSource(TimeSource.SERVER_RECEIVED);

        // ✅ 若你的欄位是 primitive boolean，這行可省略；若是 Boolean 且 NOT NULL，請保留
        log.setTimeSuspect(false);

        // ✅ 讓 worker 不會因為缺 imageObjectKey 直接取消
        log.setImageObjectKey("user-1/blobs/sha256/dummy.jpg");

        logRepo.saveAndFlush(log);

        // 2) 建 task，確保 runnable
        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        // ✅ 保險：有些 query 會用 nextRetryAtUtc <= now
        task.setNextRetryAtUtc(now.minusSeconds(1));

        // ✅ 有些 envelope 會直接把 pollAfterSec 回傳
        task.setPollAfterSec(2);

        taskRepo.saveAndFlush(task);

        // 3) 執行 worker：provider 永遠 timeout → 應標成 FAILED + code=PROVIDER_TIMEOUT
        worker.runOnce();

        var updatedLog = logRepo.findById(log.getId()).orElseThrow();
        assertThat(updatedLog.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(updatedLog.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");

        var updatedTask = taskRepo.findByFoodLogId(log.getId()).orElseThrow();
        assertThat(updatedTask.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.FAILED);
        assertThat(updatedTask.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");

        // 4) GET one：Envelope 應該把 errorCode 帶出來
        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");
        assertThat(env.status()).isEqualTo("FAILED");
        assertThat(env.error()).isNotNull();
        assertThat(env.error().errorCode()).isEqualTo("PROVIDER_TIMEOUT");

        // ✅ timeout 一般應該可重試，因此 retryAfterSec 應該有值（若你策略是 non-retryable，這個 assert 要改）
        assertThat(env.error().retryAfterSec()).isNotNull();
    }

    /**
     * ✅ 這顆 ProviderClient 只在這個 test context 存在：
     * - providerCode=GEMINI
     * - process() 永遠丟 SocketTimeoutException
     * 讓你的 ProviderErrorMapper 能穩定映射到 PROVIDER_TIMEOUT
     */
    @TestConfiguration
    static class TimeoutGeminiProviderConfig {

        private static final ObjectMapper OM = new ObjectMapper();

        @Bean
        public ProviderClient timeoutGeminiProviderClient() {
            return new ProviderClient() {
                @Override
                public String providerCode() {
                    return "GEMINI";
                }

                @Override
                public ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception {
                    // ✅ 直接丟 timeout（不要真的去讀 storage / 打外網）
                    throw new SocketTimeoutException("simulated timeout");
                }
            };
        }
    }
}
