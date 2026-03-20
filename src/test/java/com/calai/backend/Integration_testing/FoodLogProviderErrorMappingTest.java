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
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.job.worker.FoodLogTaskWorker;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
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
    void timeout_should_map_to_PROVIDER_TIMEOUT_and_cancel_task_but_surface_failed_in_getOne() throws Exception {
        Long userId = 1L;
        Instant now = Instant.now();

        FoodLogEntity log = new FoodLogEntity();
        log.setUserId(userId);
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setProvider("GEMINI");
        log.setDegradeLevel("DG-0");
        log.setCapturedAtUtc(now);
        log.setCapturedTz("Asia/Taipei");
        log.setCapturedLocalDate(LocalDate.now(ZoneId.of("Asia/Taipei")));
        log.setServerReceivedAtUtc(now);
        log.setTimeSource(TimeSource.SERVER_RECEIVED);
        log.setTimeSuspect(false);
        log.setImageObjectKey("user-1/blobs/sha256/dummy.jpg");

        logRepo.saveAndFlush(log);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId(log.getId());
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setNextRetryAtUtc(now.minusSeconds(1));
        task.setPollAfterSec(2);

        taskRepo.saveAndFlush(task);

        worker.runOnce();

        var updatedLog = logRepo.findById(log.getId()).orElseThrow();
        assertThat(updatedLog.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(updatedLog.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");

        var updatedTask = taskRepo.findByFoodLogId(log.getId()).orElseThrow();
        assertThat(updatedTask.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.CANCELLED);
        assertThat(updatedTask.getLastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(updatedTask.getNextRetryAtUtc()).isNull();

        FoodLogEnvelope env = service.getOne(userId, log.getId(), "test-req");
        assertThat(env.status()).isEqualTo("FAILED");
        assertThat(env.error()).isNotNull();
        assertThat(env.error().errorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(env.error().retryAfterSec()).isNull();
    }

    @TestConfiguration
    static class TimeoutGeminiProviderConfig {

        @Bean
        @Primary
        public ProviderClient timeoutGeminiProviderClient() {
            return new ProviderClient() {
                @Override
                public String providerCode() {
                    return "GEMINI";
                }

                @Override
                public ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception {
                    throw new SocketTimeoutException("simulated timeout");
                }
            };
        }
    }
}