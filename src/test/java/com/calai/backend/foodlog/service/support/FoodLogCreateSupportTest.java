package com.calai.backend.foodlog.service.support;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoodLogCreateSupportTest {

    @Test
    void newBaseEntity_should_fill_fields() {
        FoodLogCreateSupport support = new FoodLogCreateSupport(
                mock(StorageService.class),
                mock(IdempotencyService.class),
                mock(ImageBlobService.class),
                mock(FoodLogEffectivePostProcessor.class)
        );

        FoodLogEntity e = support.newBaseEntity(
                1L,
                "PHOTO",
                Instant.parse("2026-03-21T10:00:00Z"),
                "Asia/Taipei",
                LocalDate.of(2026, 3, 21),
                Instant.parse("2026-03-21T10:00:05Z"),
                TimeSource.EXIF,
                false
        );

        assertEquals(1L, e.getUserId());
        assertEquals("PHOTO", e.getMethod());
        assertEquals("DG-0", e.getDegradeLevel());
        assertEquals(TimeSource.EXIF, e.getTimeSource());
    }

    @Test
    void applyPendingMiss_should_set_pending_fields() {
        FoodLogCreateSupport support = new FoodLogCreateSupport(
                mock(StorageService.class),
                mock(IdempotencyService.class),
                mock(ImageBlobService.class),
                mock(FoodLogEffectivePostProcessor.class)
        );

        FoodLogEntity e = new FoodLogEntity();
        support.applyPendingMiss(e, ModelTier.MODEL_TIER_LOW, "GEMINI");

        assertEquals("DG-2", e.getDegradeLevel());
        assertEquals("GEMINI", e.getProvider());
        assertEquals(FoodLogStatus.PENDING, e.getStatus());
    }

    @Test
    void createQueuedTask_should_build_standard_task() {
        FoodLogCreateSupport support = new FoodLogCreateSupport(
                mock(StorageService.class),
                mock(IdempotencyService.class),
                mock(ImageBlobService.class),
                mock(FoodLogEffectivePostProcessor.class)
        );

        FoodLogTaskEntity t = support.createQueuedTask("log-1");

        assertEquals("log-1", t.getFoodLogId());
        assertEquals(FoodLogTaskEntity.TaskStatus.QUEUED, t.getTaskStatus());
        assertEquals(2, t.getPollAfterSec());
    }

    @Test
    void applyCacheHitDraft_should_copy_provider_and_status() {
        FoodLogEffectivePostProcessor postProcessor = mock(FoodLogEffectivePostProcessor.class);

        FoodLogCreateSupport support = new FoodLogCreateSupport(
                mock(StorageService.class),
                mock(IdempotencyService.class),
                mock(ImageBlobService.class),
                postProcessor
        );

        FoodLogEntity e = new FoodLogEntity();
        e.setMethod("PHOTO");

        FoodLogEntity hit = new FoodLogEntity();
        hit.setProvider("GEMINI");
        hit.setDegradeLevel("DG-2");

        ObjectNode effective = JsonNodeFactory.instance.objectNode();
        effective.put("foodName", "Apple");
        hit.setEffective(effective);

        ObjectNode processed = effective.deepCopy();
        when(postProcessor.apply(processed, "GEMINI", "PHOTO")).thenReturn(processed);

        support.applyCacheHitDraft(e, hit);

        assertEquals("GEMINI", e.getProvider());
        assertEquals("DG-2", e.getDegradeLevel());
        assertEquals(FoodLogStatus.DRAFT, e.getStatus());
    }
}
