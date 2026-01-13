package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.service.FoodLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/food-logs")
public class FoodLogController {

    private final AuthContext auth;
    private final FoodLogService service;

    @PostMapping(value = "/album", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FoodLogEnvelope album(
            @RequestHeader(value = "X-Client-Timezone", required = false) String clientTz,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest req
    ) throws Exception {
        Long uid = auth.requireUserId();
        String requestId = requestId(req);
        return service.createAlbum(uid, clientTz, file, requestId);
    }

    @GetMapping("/{id}")
    public FoodLogEnvelope getOne(@PathVariable String id, HttpServletRequest req) {
        Long uid = auth.requireUserId();
        String requestId = requestId(req);
        return service.getOne(uid, id, requestId);
    }

    private static String requestId(HttpServletRequest req) {
        String v = req.getHeader("X-Request-Id");
        return (v == null || v.isBlank()) ? UUID.randomUUID().toString() : v;
    }

    /**
     * ✅ Dev-only：取回原始圖片（上線要關）
     * 開關：app.features.dev-image-endpoint=true
     */
    @ConditionalOnProperty(name = "app.features.dev-image-endpoint", havingValue = "true")
    @GetMapping(
            value = "/{id}/image",
            produces = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE }
    )
    public ResponseEntity<StreamingResponseBody> getImage(@PathVariable String id) {
        Long uid = auth.requireUserId();

        var opened = service.openImage(uid, id);

        StreamingResponseBody body = outputStream -> {
            try (var in = service.openImageStream(opened.objectKey())) {
                in.transferTo(outputStream);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        String ct = opened.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : opened.contentType();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ct)
                .headers(h -> {
                    if (opened.sizeBytes() > 0) {
                        h.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(opened.sizeBytes()));
                    }
                })
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

}
