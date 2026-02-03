package com.calai.backend.foodlog;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogDeleteService;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.ImageBlobService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodLogDeleteServiceTest {

    @Test
    void delete_should_cancel_task_and_enqueue_job() {
        FoodLogRepository logRepo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        DeletionJobRepository deletionRepo = mock(DeletionJobRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        FoodLogService foodLogService = mock(FoodLogService.class);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("L1");
        log.setUserId(10L);
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageSha256("sha");
        log.setImageObjectKey("blobKey");

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("T1");
        task.setFoodLogId("L1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.RUNNING);

        when(logRepo.findByIdForUpdate("L1")).thenReturn(log);
        when(taskRepo.findByFoodLogIdForUpdate("L1")).thenReturn(Optional.of(task));
        when(blobService.findExtOrNull(10L, "sha")).thenReturn(".jpg");
        when(foodLogService.getOne(10L, "L1", "RID")).thenReturn(null);

        FoodLogDeleteService svc = new FoodLogDeleteService(logRepo, taskRepo, deletionRepo, blobService, foodLogService);
        svc.deleteOne(10L, "L1", "RID");

        assertEquals(FoodLogStatus.DELETED, log.getStatus());
        verify(taskRepo).save(argThat(t -> t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.CANCELLED));
        verify(deletionRepo).save(any());
    }
}
