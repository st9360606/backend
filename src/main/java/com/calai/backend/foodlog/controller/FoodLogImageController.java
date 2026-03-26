package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.service.FoodLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@RestController
public class FoodLogImageController {

    private final AuthContext auth;
    private final FoodLogService service;

    @GetMapping("/api/v1/food-logs/{id}/image")
    public ResponseEntity<StreamingResponseBody> image(
            @PathVariable("id") String foodLogId
    ) {
        Long uid = auth.requireUserId();

        var opened = service.openImage(uid, foodLogId);

        StreamingResponseBody body = outputStream -> {
            try (InputStream in = service.openImageStream(opened.objectKey())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    outputStream.write(buf, 0, n);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        HttpHeaders headers = new HttpHeaders();
        String ct = (opened.contentType() == null || opened.contentType().isBlank())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : opened.contentType();
        headers.set(HttpHeaders.CONTENT_TYPE, ct);

        // Home recent-upload 預覽圖：允許 private cache，但避免被共享代理快取
        headers.setCacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate());

        if (opened.sizeBytes() > 0) {
            headers.setContentLength(opened.sizeBytes());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }
}
