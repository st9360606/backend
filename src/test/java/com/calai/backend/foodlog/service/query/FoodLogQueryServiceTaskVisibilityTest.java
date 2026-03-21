package com.calai.backend.foodlog.service.query;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogQueryServiceTaskVisibilityTest {

    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;
    @Mock FoodLogEnvelopeAssembler envelopeAssembler;

    private FoodLogQueryService service;

    @BeforeEach
    void setUp() {
        service = new FoodLogQueryService(repo, taskRepo, envelopeAssembler);
    }

    @Test
    void pending_should_query_task_and_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-1");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.PENDING);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-1");
        t.setFoodLogId("log-1");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdAndUserId("log-1", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-1")).thenReturn(Optional.of(t));
        when(envelopeAssembler.assemble(e, t, "rid-1")).thenReturn(expected);

        FoodLogEnvelope actual = service.getOne(1L, "log-1", "rid-1");

        assertNotNull(actual);
        verify(taskRepo).findByFoodLogId("log-1");
        verify(envelopeAssembler).assemble(e, t, "rid-1");
    }

    @Test
    void draft_should_not_query_task_and_should_not_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-2");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.DRAFT);

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdAndUserId("log-2", 1L)).thenReturn(Optional.of(e));
        when(envelopeAssembler.assemble(e, null, "rid-2")).thenReturn(expected);

        FoodLogEnvelope actual = service.getOne(1L, "log-2", "rid-2");

        assertNotNull(actual);
        verify(taskRepo, never()).findByFoodLogId(anyString());
        verify(envelopeAssembler).assemble(e, null, "rid-2");
    }

    @Test
    void failed_should_query_task_and_include_task_when_task_status_is_failed() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-3");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.FAILED);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-3");
        t.setFoodLogId("log-3");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdAndUserId("log-3", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-3")).thenReturn(Optional.of(t));
        when(envelopeAssembler.assemble(e, t, "rid-3")).thenReturn(expected);

        FoodLogEnvelope actual = service.getOne(1L, "log-3", "rid-3");

        assertNotNull(actual);
        verify(taskRepo).findByFoodLogId("log-3");
        verify(envelopeAssembler).assemble(e, t, "rid-3");
    }

    @Test
    void failed_should_not_include_task_when_task_status_is_success() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-4");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.FAILED);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-4");
        t.setFoodLogId("log-4");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.SUCCEEDED);

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdAndUserId("log-4", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-4")).thenReturn(Optional.of(t));
        when(envelopeAssembler.assemble(e, null, "rid-4")).thenReturn(expected);

        FoodLogEnvelope actual = service.getOne(1L, "log-4", "rid-4");

        assertNotNull(actual);
        verify(taskRepo).findByFoodLogId("log-4");
        verify(envelopeAssembler).assemble(e, null, "rid-4");
    }
}
