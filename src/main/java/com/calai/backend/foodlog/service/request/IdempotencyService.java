package com.calai.backend.foodlog.service.request;

import com.calai.backend.foodlog.repo.FoodLogRequestRepository;
import com.calai.backend.foodlog.web.error.RequestInProgressException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
@Service
public class IdempotencyService {

    private final FoodLogRequestRepository repo;
    private final Clock clock;

    /**
     * @return 已存在的 foodLogId；若回 null 代表你取得了 RESERVED，可以繼續做真正流程
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String reserveOrGetExisting(Long userId, String requestId, Instant now) {
        if (requestId == null || requestId.isBlank()) return null;

        String existing = repo.findFoodLogId(userId, requestId);
        if (existing != null && !existing.isBlank()) return existing;

        String status = repo.findStatus(userId, requestId);
        if ("FAILED".equalsIgnoreCase(status)) {
            repo.deleteFailedIfNotAttached(userId, requestId);
        }

        int inserted = repo.reserve(userId, requestId, now);
        if (inserted == 1) return null;

        status = repo.findStatus(userId, requestId);
        if ("RESERVED".equalsIgnoreCase(status)) {
            throw new RequestInProgressException("REQUEST_IN_PROGRESS", 1);
        }

        existing = repo.findFoodLogId(userId, requestId);
        if (existing != null && !existing.isBlank()) return existing;

        throw new RequestInProgressException("REQUEST_IN_PROGRESS", 1);
    }

    @Transactional
    public void attach(Long userId, String requestId, String foodLogId, Instant now) {
        if (requestId == null || requestId.isBlank()) return;
        repo.attach(userId, requestId, foodLogId, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAndReleaseIfNeeded(
            Long userId,
            String requestId,
            boolean releaseIfNotAttached
    ) {
        if (requestId == null || requestId.isBlank()) return;

        if (releaseIfNotAttached) {
            repo.releaseIfNotAttached(userId, requestId);
            return;
        }

        repo.releaseIfNotAttached(userId, requestId);
    }
}
