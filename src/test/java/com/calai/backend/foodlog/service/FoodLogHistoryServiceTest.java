package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FoodLogHistoryServiceTest {

    @Test
    void save_should_turn_draft_to_saved() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);
        Clock clock = mock(Clock.class);
        Instant savedAtUtc = Instant.parse("2026-03-21T08:15:00Z");

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.DRAFT);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));
        when(clock.instant()).thenReturn(savedAtUtc);

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService, clock);
        svc.save(10L, "L1", "RID");

        assertEquals(FoodLogStatus.SAVED, e.getStatus());
        assertEquals(savedAtUtc, e.getSavedAtUtc());
        verify(repo).save(e);
        verify(foodLogService).getOne(10L, "L1", "RID");
    }

    @Test
    void save_pending_should_throw_conflict_code() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);
        Clock clock = mock(Clock.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.PENDING);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService, clock);

        var ex = assertThrows(FoodLogAppException.class, () ->
                svc.save(10L, "L1", "RID")
        );
        assertEquals("FOOD_LOG_NOT_READY", ex.getMessage());
    }

    @Test
    void save_saved_should_be_idempotent() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);
        Clock clock = mock(Clock.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.SAVED);

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService, clock);
        svc.save(10L, "L1", "RID");

        verify(repo, never()).save(any());
        verify(foodLogService).getOne(10L, "L1", "RID");
    }

    @Test
    void list_recent_previews_should_include_created_at_utc() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-21T08:00:00Z"), ZoneOffset.UTC);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L2");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.DRAFT);
        e.setMethod("PHOTO");
        e.setProvider("GEMINI");
        e.setCreatedAtUtc(Instant.parse("2026-03-21T07:30:00Z"));
        e.setUpdatedAtUtc(Instant.parse("2026-03-21T07:45:00Z"));
        e.setSavedAtUtc(Instant.parse("2026-03-21T07:50:00Z"));

        when(repo.findRecentPreviewItems(
                eq(10L),
                eq(Instant.parse("2026-03-21T05:00:00Z")),
                eq(10)
        )).thenReturn(List.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService, clock);
        var response = svc.listRecentPreviews(10L, 3, 10, "RID");

        assertEquals(1, response.items().size());
        assertEquals("2026-03-21T07:30:00Z", response.items().get(0).createdAtUtc());
        assertEquals("2026-03-21T07:45:00Z", response.items().get(0).updatedAtUtc());
        assertEquals("2026-03-21T07:50:00Z", response.items().get(0).savedAtUtc());
    }

    @Test
    void unsave_should_clear_saved_at_utc() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogService foodLogService = mock(FoodLogService.class);
        Clock clock = mock(Clock.class);

        FoodLogEntity e = new FoodLogEntity();
        e.setId("L1");
        e.setUserId(10L);
        e.setStatus(FoodLogStatus.SAVED);
        e.setSavedAtUtc(Instant.parse("2026-03-21T08:15:00Z"));

        when(repo.findByIdForUpdate("L1")).thenReturn(Optional.of(e));

        FoodLogHistoryService svc = new FoodLogHistoryService(repo, foodLogService, clock);
        svc.unsave(10L, "L1", "RID");

        assertEquals(FoodLogStatus.DRAFT, e.getStatus());
        assertNull(e.getSavedAtUtc());
        verify(repo).save(e);
        verify(foodLogService).getOne(10L, "L1", "RID");
    }
}
