package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.service.FoodLogService;
import jakarta.servlet.http.HttpServletRequest;
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
            @PathVariable("id") String foodLogId,
            HttpServletRequest req
    ) {
        Long uid = auth.requireUserId();
        String requestId = RequestIdFilter.getOrCreate(req);

        // 1) 查出 objectKey / contentType / size
        var opened = service.openImage(uid, foodLogId);

        // 2) 串流回傳（StreamingResponseBody 會在完成後關閉輸出）
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

        // 若你希望更保守（避免被第三方代理快取），改成 CacheControl.noStore()
        headers.setCacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate());

        // 有 size 就給 Content-Length（有助於 App 顯示 loading）
        if (opened.sizeBytes() > 0) headers.setContentLength(opened.sizeBytes());

        // trace（你想也可放 header，這裡先不放避免污染）
        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }
}
