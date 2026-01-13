package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.repo.FoodLogRequestRepository;
import com.calai.backend.foodlog.web.RequestInProgressException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class IdempotencyService {

    private final FoodLogRequestRepository repo;

    /**
     * @return 已存在的 foodLogId；若回 null 代表你取得了 RESERVED，可以繼續做真正流程
     */
    @Transactional
    public String reserveOrGetExisting(Long userId, String requestId, Instant now) {
        if (requestId == null || requestId.isBlank()) return null; // 理論上不會發生（你已統一用 RequestIdFilter）

        String existing = repo.findFoodLogId(userId, requestId);
        if (existing != null && !existing.isBlank()) return existing;

        int inserted = repo.reserve(userId, requestId, now);
        if (inserted == 1) {
            // 你是第一個拿到 RESERVED 的人
            return null;
        }

        // 已有人 reserve 了，但還沒 attach foodLogId
        String status = repo.findStatus(userId, requestId);
        if ("RESERVED".equalsIgnoreCase(status)) {
            throw new RequestInProgressException("REQUEST_IN_PROGRESS", 1);
        }

        // 其他狀態就再查一次看看
        existing = repo.findFoodLogId(userId, requestId);
        if (existing != null && !existing.isBlank()) return existing;

        throw new RequestInProgressException("REQUEST_IN_PROGRESS", 1);
    }

    @Transactional
    public void attach(Long userId, String requestId, String foodLogId, Instant now) {
        if (requestId == null || requestId.isBlank()) return;
        repo.attach(userId, requestId, foodLogId, now);
    }

    @Transactional
    public void failAndReleaseIfNeeded(Long userId, String requestId, String code, String msg, boolean releaseIfNotAttached) {
        if (requestId == null || requestId.isBlank()) return;
        repo.markFailed(userId, requestId, code, msg, Instant.now());
        if (releaseIfNotAttached) {
            repo.releaseIfNotAttached(userId, requestId);
        }
    }
}
