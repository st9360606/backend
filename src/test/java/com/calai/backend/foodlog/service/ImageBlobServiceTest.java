package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.ImageBlobEntity;
import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageBlobServiceTest {

    @Test
    void retain_new_blob_should_move_temp_to_blobKey() throws Exception {
        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        StorageService storage = mock(StorageService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-03T00:00:00Z"), ZoneOffset.UTC);

        // insertFirst=1 => 新建 blob
        when(repo.insertFirst(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);

        // storage.exists(blobKey)=false => move(temp -> blobKey)
        when(storage.exists(anyString())).thenReturn(false);

        ImageBlobService svc = new ImageBlobService(repo, storage, clock);

        ImageBlobService.RetainResult r =
                svc.retainFromTemp(1L, "tmpKey", "sha", ".jpg", "image/jpeg", 123);

        // 會 move 到 user-1/blobs/sha256/sha.jpg
        verify(storage).move(eq("tmpKey"), contains("user-1/blobs/sha256/sha.jpg"));
        verify(storage, never()).delete(eq("tmpKey"));

        // 回傳 objectKey 也應該是 blobKey
        assertEquals("user-1/blobs/sha256/sha.jpg", r.objectKey());
        assertEquals("sha", r.sha256());
        assertEquals(true, r.newlyCreated());
    }

    @Test
    void retain_existing_blob_should_delete_temp_increment_refcount_and_return_db_objectKey() throws Exception {
        ImageBlobRepository repo = mock(ImageBlobRepository.class);
        StorageService storage = mock(StorageService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-03T00:00:00Z"), ZoneOffset.UTC);

        // insertFirst=0 => 表示 DB 已存在 (或 unique hit)
        when(repo.insertFirst(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(0);

        // retain 必須回 1 才表示更新成功
        when(repo.retain(eq(1L), eq("sha"), any())).thenReturn(1);

        // 既有分支新版會去抓 DB row 當 objectKey source of truth
        ImageBlobEntity row = new ImageBlobEntity();
        row.setUserId(1L);
        row.setSha256("sha");
        row.setObjectKey("user-1/blobs/sha256/sha.jpg");
        row.setExt(".jpg");
        row.setRefCount(7);

        when(repo.findByUserIdAndSha256(1L, "sha")).thenReturn(Optional.of(row));

        ImageBlobService svc = new ImageBlobService(repo, storage, clock);

        // 呼叫端傳 ext=.png（故意不一致）
        ImageBlobService.RetainResult r =
                svc.retainFromTemp(1L, "tmpKey", "sha", ".png", "image/png", 999);

        verify(repo).retain(eq(1L), eq("sha"), any());
        verify(repo).findByUserIdAndSha256(eq(1L), eq("sha"));

        // 既有分支會刪 temp
        verify(storage).delete(eq("tmpKey"));

        // 既有分支不應 move
        verify(storage, never()).move(anyString(), anyString());

        // 回傳應以 DB 的 objectKey 為準
        assertEquals("user-1/blobs/sha256/sha.jpg", r.objectKey());
        assertEquals("sha", r.sha256());
        assertEquals(false, r.newlyCreated());
    }
}