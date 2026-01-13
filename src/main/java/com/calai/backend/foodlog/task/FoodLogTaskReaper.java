package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
@Component
public class FoodLogTaskReaper {

    private static final Duration RUNNING_TIMEOUT = Duration.ofMinutes(2);

    private final FoodLogTaskRepository taskRepo;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void reap() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(RUNNING_TIMEOUT);

        int n = taskRepo.resetStaleRunning(
                staleBefore,
                now,
                "WORKER_STALE_RUNNING",
                "RUNNING timeout, reset to FAILED"
        );

        if (n > 0) {
            log.warn("reaped stale RUNNING tasks: count={}", n);
        }
    }
}
