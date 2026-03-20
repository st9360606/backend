package com.calai.backend.foodlog;

import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.job.worker.DeletionJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeletionJobWorkerTest {

    @Test
    void fallbackDelete_onlyWhenNoLiveRefs() throws Exception {
        DeletionJobRepository repo = mock(DeletionJobRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        FoodLogRepository foodLogRepo = mock(FoodLogRepository.class);
        StorageService storage = mock(StorageService.class);
        PlatformTransactionManager txManager = mockTxManager();
        Clock clock = Clock.fixed(Instant.parse("2026-03-03T00:00:00Z"), ZoneOffset.UTC);

        DeletionJobWorker worker = new DeletionJobWorker(
                repo, blobService, foodLogRepo, storage, txManager, clock
        );

        DeletionJobEntity job = new DeletionJobEntity();
        job.setId("job-1");
        job.setUserId(10L);
        job.setFoodLogId("log-1");
        job.setSha256("abc");
        job.setImageObjectKey("user-10/blobs/abc.jpg");
        job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
        job.setAttempts(0);

        when(repo.claimRunnableForUpdate(any(Instant.class), eq(20)))
                .thenReturn(List.of(job));

        // ✅ worker 會在 prepareExecution / markSucceeded 再查一次 row
        when(repo.findByIdForUpdate("job-1"))
                .thenReturn(Optional.of(job));

        // ✅ 新版 API：不再 throw，改回傳 outcome
        when(blobService.release(10L, "abc"))
                .thenReturn(ImageBlobService.ReleaseOutcome.ROW_MISSING);

        when(foodLogRepo.countLiveRefsByObjectKey(
                10L, "user-10/blobs/abc.jpg", FoodLogStatus.DELETED
        )).thenReturn(0L);

        when(storage.exists("user-10/blobs/abc.jpg")).thenReturn(true);

        worker.runOnce();

        // refs==0 → 走 fallback cleanup，優先 move
        verify(storage, times(1))
                .move(eq("user-10/blobs/abc.jpg"), contains("blobs/trash/deletion-job-job-1/"));
        verify(storage, never()).delete("user-10/blobs/abc.jpg");

        // 最終應為 SUCCEEDED
        assertEquals(DeletionJobEntity.JobStatus.SUCCEEDED, job.getJobStatus());
    }

    @Test
    void fallbackDelete_skippedWhenReferenced() throws Exception {
        DeletionJobRepository repo = mock(DeletionJobRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        FoodLogRepository foodLogRepo = mock(FoodLogRepository.class);
        StorageService storage = mock(StorageService.class);
        PlatformTransactionManager txManager = mockTxManager();
        Clock clock = Clock.fixed(Instant.parse("2026-03-03T00:00:00Z"), ZoneOffset.UTC);

        DeletionJobWorker worker = new DeletionJobWorker(
                repo, blobService, foodLogRepo, storage, txManager, clock
        );

        DeletionJobEntity job = new DeletionJobEntity();
        job.setId("job-2");
        job.setUserId(10L);
        job.setFoodLogId("log-2");
        job.setSha256("abc");
        job.setImageObjectKey("user-10/blobs/abc.jpg");
        job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
        job.setAttempts(0);

        when(repo.claimRunnableForUpdate(any(Instant.class), eq(20)))
                .thenReturn(List.of(job));

        when(repo.findByIdForUpdate("job-2"))
                .thenReturn(Optional.of(job));

        when(blobService.release(10L, "abc"))
                .thenReturn(ImageBlobService.ReleaseOutcome.ROW_MISSING);

        when(foodLogRepo.countLiveRefsByObjectKey(
                10L, "user-10/blobs/abc.jpg", FoodLogStatus.DELETED
        )).thenReturn(2L);

        worker.runOnce();

        // refs>0 → 不搬、不刪
        verify(storage, never()).move(anyString(), anyString());
        verify(storage, never()).delete(anyString());

        // 最終應為 CANCELLED
        assertEquals(DeletionJobEntity.JobStatus.CANCELLED, job.getJobStatus());
    }

    private static PlatformTransactionManager mockTxManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        when(txManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        doNothing().when(txManager).commit(any(TransactionStatus.class));
        doNothing().when(txManager).rollback(any(TransactionStatus.class));

        return txManager;
    }
}