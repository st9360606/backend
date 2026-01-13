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
        Mockito.when(repo.reserve(eq(1L), eq("rid"), any())).thenReturn(1);

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
        Mockito.when(repo.reserve(eq(1L), eq("rid"), any())).thenReturn(0);
        Mockito.when(repo.findStatus(eq(1L), eq("rid"))).thenReturn("RESERVED");

        IdempotencyService s = new IdempotencyService(repo);

        assertThrows(RequestInProgressException.class,
                () -> s.reserveOrGetExisting(1L, "rid", Instant.now()));
    }
}
