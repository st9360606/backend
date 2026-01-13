package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceTaskVisibilityTest {

    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;

    private FoodLogService service;

    @BeforeEach
    void setUp() {
        service = new FoodLogService(repo, taskRepo, new ObjectMapper());
    }

    @Test
    void pending_should_query_task_and_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-1");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.PENDING);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");
        e.setEffective(null);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-1");
        t.setFoodLogId("log-1");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        t.setPollAfterSec(2);

        when(repo.findByIdAndUserId("log-1", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-1")).thenReturn(Optional.of(t));

        FoodLogEnvelope env = service.getOne(1L, "log-1", "rid-1");

        assertNotNull(env.task());
        assertEquals("task-1", env.task().taskId());

        verify(taskRepo, times(1)).findByFoodLogId("log-1");
    }

    @Test
    void draft_should_not_query_task_and_should_not_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-2");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.DRAFT);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");
        e.setEffective(null);

        when(repo.findByIdAndUserId("log-2", 1L)).thenReturn(Optional.of(e));

        FoodLogEnvelope env = service.getOne(1L, "log-2", "rid-2");

        assertNull(env.task());
        verify(taskRepo, never()).findByFoodLogId(anyString());
    }
}
