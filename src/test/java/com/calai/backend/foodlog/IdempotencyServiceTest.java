package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.FoodLogRequestRepository;
import com.calai.backend.foodlog.service.IdempotencyService;
import com.calai.backend.foodlog.web.RequestInProgressException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class IdempotencyServiceTest {

    @Test
    void reserve_first_time_should_return_null() {
        FoodLogRequestRepository repo = Mockito.mock(FoodLogRequestRepository.class);
        Mockito.when(repo.findFoodLogId(eq(1L), eq("rid"))).thenReturn(null);
        Mockito.when(repo.reserve(eq(1L), eq("rid"), any(Instant.class))).thenReturn(1);

        IdempotencyService s = new IdempotencyService(repo);
        String existing = s.reserveOrGetExisting(1L, "rid", Instant.now());

        assertNull(existing);
    }

    @Test
    void reserve_duplicate_should_return_existing_log_id() {
        FoodLogRequestRepository repo = Mockito.mock(FoodLogRequestRepository.class);
        Mockito.when(repo.findFoodLogId(eq(1L), eq("rid"))).thenReturn("log-123");

        IdempotencyService s = new IdempotencyService(repo);
        String existing = s.reserveOrGetExisting(1L, "rid", Instant.now());

        assertEquals("log-123", existing);
    }

    @Test
    void reserve_in_progress_should_throw_409() {
        FoodLogRequestRepository repo = Mockito.mock(FoodLogRequestRepository.class);
        Mockito.when(repo.findFoodLogId(eq(1L), eq("rid"))).thenReturn(null);
        Mockito.when(repo.reserve(eq(1L), eq("rid"), any(Instant.class))).thenReturn(0);
        Mockito.when(repo.findStatus(eq(1L), eq("rid"))).thenReturn("RESERVED");

        IdempotencyService s = new IdempotencyService(repo);

        assertThrows(RequestInProgressException.class,
                () -> s.reserveOrGetExisting(1L, "rid", Instant.now()));
    }

    @Test
    void reserveOrGetExisting_shouldReleaseFailedAndReserveAgain() {
        FoodLogRequestRepository repo = Mockito.mock(FoodLogRequestRepository.class);
        IdempotencyService s = new IdempotencyService(repo);

        Long userId = 1L;
        String requestId = "req-1";
        Instant now = Instant.now();

        Mockito.when(repo.findFoodLogId(userId, requestId)).thenReturn(null);

        // ✅ 原本 thenReturn("FAILED", null) 會遇到 varargs + null 型別不精確
        // ✅ 改成鏈式 thenReturn，並明確轉型 null
        Mockito.when(repo.findStatus(userId, requestId))
                .thenReturn("FAILED")
                .thenReturn((String) null); // 第二次呼叫回 null（或你也可以回 "RESERVED"/"OK" 視你的實作）

        Mockito.when(repo.reserve(userId, requestId, now)).thenReturn(1); // 釋放後成功插入

        String existing = s.reserveOrGetExisting(userId, requestId, now);

        assertNull(existing);
        Mockito.verify(repo).deleteFailedIfNotAttached(userId, requestId);
        Mockito.verify(repo).reserve(userId, requestId, now);
    }

    @Test
    void reserveOrGetExisting_whenReservedByOther_shouldThrowRequestInProgress() {
        FoodLogRequestRepository repo = Mockito.mock(FoodLogRequestRepository.class);
        IdempotencyService s = new IdempotencyService(repo);

        Long userId = 1L;
        String requestId = "req-2";
        Instant now = Instant.now();

        Mockito.when(repo.findFoodLogId(userId, requestId)).thenReturn(null);
        Mockito.when(repo.findStatus(userId, requestId)).thenReturn("RESERVED");
        Mockito.when(repo.reserve(userId, requestId, now)).thenReturn(0); // INSERT IGNORE 沒插入表示已存在

        assertThrows(RequestInProgressException.class, () -> s.reserveOrGetExisting(userId, requestId, now));
    }
}
