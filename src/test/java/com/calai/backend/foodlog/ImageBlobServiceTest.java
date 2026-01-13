package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageBlobServiceTest {

    @Test
    void retain_new_blob_should_move_temp_to_blobKey() throws Exception {
        ImageBlobRepository repo = Mockito.mock(ImageBlobRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);

        when(repo.insertFirst(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(storage.exists(anyString())).thenReturn(false);

        ImageBlobService svc = new ImageBlobService(repo, storage);
        svc.retainFromTemp(1L, "tmpKey", "sha", ".jpg", "image/jpeg", 123);

        verify(storage).move(eq("tmpKey"), contains("/blobs/sha256/sha.jpg"));
        verify(storage, never()).delete(eq("tmpKey"));
    }

    @Test
    void retain_existing_blob_should_delete_temp_and_increment_refcount() throws Exception {
        ImageBlobRepository repo = Mockito.mock(ImageBlobRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);

        when(repo.insertFirst(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(0);

        ImageBlobService svc = new ImageBlobService(repo, storage);
        svc.retainFromTemp(1L, "tmpKey", "sha", ".png", "image/png", 999);

        verify(repo).retain(eq(1L), eq("sha"), any());
        verify(storage).delete(eq("tmpKey"));
        verify(storage, never()).move(anyString(), anyString());
    }
}
