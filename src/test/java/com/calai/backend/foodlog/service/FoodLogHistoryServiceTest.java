package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodLogHistoryServiceTest {

    @Test
    void save_should_turn_draft_to_saved() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.DRAFT);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService);
        svc.save(10L, "L1", "RID");

        assertEquals(FoodLogStatus.SAVED, e.getStatus());
        verify(repo).save(e);
        verify(foodLogService).getOne(10L, "L1", "RID");
    }

    @Test
    void save_pending_should_throw_conflict_code() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.PENDING);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService);

        var ex = assertThrows(FoodLogAppException.class, () ->
                svc.save(10L, "L1", "RID")
        );
        assertEquals("FOOD_LOG_NOT_READY", ex.getMessage());
    }

    @Test
    void save_saved_should_be_idempotent() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.SAVED);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService);
        svc.save(10L, "L1", "RID");

        verify(repo, never()).save(any());
        verify(foodLogService).getOne(10L, "L1", "RID");
    }
}