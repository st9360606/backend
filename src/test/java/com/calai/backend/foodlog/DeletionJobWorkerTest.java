package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.DeletionJobWorker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DeletionJobWorkerTest {

    @Test
    void fallbackDelete_onlyWhenNoLiveRefs() throws Exception {
        DeletionJobRepository repo = mock(DeletionJobRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        FoodLogRepository foodLogRepo = mock(FoodLogRepository.class);
        StorageService storage = mock(StorageService.class);

        DeletionJobWorker worker = new DeletionJobWorker(repo, blobService, foodLogRepo, storage);

        DeletionJobEntity job = new DeletionJobEntity();
        job.setId("job-1");
        job.setUserId(10L);
        job.setFoodLogId("log-1");
        job.setSha256("abc"); // 有 sha/ext 但 blobService.release 會丟 blob row missing
        job.setExt(".jpg");
        job.setImageObjectKey("user-10/blobs/abc.jpg");
        job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);

        when(repo.claimRunnableForUpdate(any(Instant.class), eq(20))).thenReturn(List.of(job));
        doThrow(new IllegalStateException("BLOB_ROW_NOT_FOUND")).when(blobService).release(10L, "abc", ".jpg");
        when(foodLogRepo.countLiveRefsByObjectKey(10L, "user-10/blobs/abc.jpg", FoodLogStatus.DELETED)).thenReturn(0L);
        when(storage.exists("user-10/blobs/abc.jpg")).thenReturn(true);

        worker.runOnce();

        // ✅ refs==0 → 會 move 或 delete（此測試用 move 成功路徑）
        verify(storage, times(1)).move(eq("user-10/blobs/abc.jpg"), anyString());
        verify(storage, never()).delete("user-10/blobs/abc.jpg");

        // ✅ 最終狀態應該 SUCCEEDED
        ArgumentCaptor<DeletionJobEntity> cap = ArgumentCaptor.forClass(DeletionJobEntity.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        DeletionJobEntity last = cap.getValue();
        assertEquals(DeletionJobEntity.JobStatus.SUCCEEDED, last.getJobStatus());
    }

    @Test
    void fallbackDelete_skippedWhenReferenced() throws Exception {
        DeletionJobRepository repo = mock(DeletionJobRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        FoodLogRepository foodLogRepo = mock(FoodLogRepository.class);
        StorageService storage = mock(StorageService.class);

        DeletionJobWorker worker = new DeletionJobWorker(repo, blobService, foodLogRepo, storage);

        DeletionJobEntity job = new DeletionJobEntity();
        job.setId("job-2");
        job.setUserId(10L);
        job.setFoodLogId("log-2");
        job.setSha256("abc");
        job.setExt(".jpg");
        job.setImageObjectKey("user-10/blobs/abc.jpg");
        job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);

        when(repo.claimRunnableForUpdate(any(Instant.class), eq(20))).thenReturn(List.of(job));
        doThrow(new IllegalStateException("BLOB_ROW_NOT_FOUND")).when(blobService).release(10L, "abc", ".jpg");
        when(foodLogRepo.countLiveRefsByObjectKey(10L, "user-10/blobs/abc.jpg", FoodLogStatus.DELETED)).thenReturn(2L);

        worker.runOnce();

        // ✅ refs>0 → 不刪不搬
        verify(storage, never()).move(anyString(), anyString());
        verify(storage, never()).delete(anyString());

        ArgumentCaptor<DeletionJobEntity> cap = ArgumentCaptor.forClass(DeletionJobEntity.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        DeletionJobEntity last = cap.getValue();
        assertEquals(DeletionJobEntity.JobStatus.CANCELLED, last.getJobStatus());
    }
}
