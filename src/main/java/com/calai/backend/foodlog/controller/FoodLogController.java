package com.calai.backend.foodlog.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.service.FoodLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}
