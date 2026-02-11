package com.calai.backend.foodlog;

import com.calai.backend.auth.security.AccessTokenFilter;
import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.controller.FoodLogController;
import com.calai.backend.foodlog.controller.FoodLogImageController;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.web.FoodLogExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(
        controllers = { FoodLogController.class, FoodLogImageController.class }, // ✅ 關鍵：把 image controller 加進來
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AccessTokenFilter.class)
        }
)
@Import({FoodLogExceptionAdvice.class, RequestIdFilter.class})
@TestPropertySource(properties = {
        "app.features.dev-image-endpoint=true"
})
class FoodLogExceptionAdviceTest {

    @Autowired MockMvc mvc;

    @MockitoBean AuthContext auth;
    @MockitoBean FoodLogService service;

    @MockitoBean com.calai.backend.foodlog.service.FoodLogDeleteService deleteService;
    @MockitoBean com.calai.backend.foodlog.service.FoodLogOverrideService overrideService;
    @MockitoBean com.calai.backend.foodlog.service.FoodLogHistoryService historyService;

    @Test
    void upload_unsupported_format_should_400_with_requestId() throws Exception {
        Mockito.when(auth.requireUserId()).thenReturn(1L);
        Mockito.when(service.createAlbum(
                eq(1L),
                anyString(),                 // clientTz
                anyString(),                 // deviceId
                any(MultipartFile.class),    // file  ✅
                anyString()                  // requestId
        )).thenThrow(new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT"));

        MockMultipartFile f = new MockMultipartFile(
                "file", "x.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1,2,3}
        );

        mvc.perform(multipart("/api/v1/food-logs/album")
                        .file(f)
                        .header("X-Client-Timezone", "Asia/Taipei")
                        .header("X-Device-Id", "bc-test-1")
                        .header("X-Request-Id", "RID-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "RID-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"))
                .andExpect(jsonPath("$.requestId").value("RID-123"));
    }

    @Test
    void get_not_found_should_404() throws Exception {
        Mockito.when(auth.requireUserId()).thenReturn(1L);
        Mockito.when(service.getOne(eq(1L), eq("nope"), anyString()))
                .thenThrow(new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        mvc.perform(get("/api/v1/food-logs/nope").header("X-Request-Id", "RID-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "RID-404"))
                .andExpect(jsonPath("$.code").value("FOOD_LOG_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("RID-404"));
    }

    @Test
    void image_object_missing_should_409() throws Exception {
        Mockito.when(auth.requireUserId()).thenReturn(1L);
        Mockito.when(service.openImage(eq(1L), eq("id-1")))
                .thenThrow(new IllegalStateException("IMAGE_OBJECT_KEY_MISSING"));

        mvc.perform(get("/api/v1/food-logs/id-1/image").header("X-Request-Id", "RID-409"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Request-Id", "RID-409"))
                .andExpect(jsonPath("$.code").value("IMAGE_OBJECT_KEY_MISSING"))
                .andExpect(jsonPath("$.requestId").value("RID-409"));
    }

    @Test
    void image_happy_path_should_stream_bytes_and_headers() throws Exception {
        Mockito.when(auth.requireUserId()).thenReturn(1L);

        Mockito.when(service.openImage(eq(1L), eq("id-1")))
                .thenReturn(new FoodLogService.OpenedImage("obj-1", "image/jpeg", 3));

        Mockito.when(service.openImageStream(eq("obj-1")))
                .thenReturn(new ByteArrayInputStream(new byte[]{9, 8, 7}));

        // ✅ 第一步：觸發 async
        MvcResult result = mvc.perform(get("/api/v1/food-logs/id-1/image")
                        .header("X-Request-Id", "RID-200"))
                .andExpect(request().asyncStarted())   // ✅ 這行會讓測試更「鎖行為」
                .andReturn();

        // ✅ 第二步：dispatch async 結果，才會有 body
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "RID-200"))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(content().bytes(new byte[]{9, 8, 7}));
    }
}
