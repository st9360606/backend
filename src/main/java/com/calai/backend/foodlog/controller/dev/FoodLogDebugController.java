package com.calai.backend.foodlog.controller.dev;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Profile({"dev","local"})
@ConditionalOnProperty(prefix = "app.features", name = "dev-debug-endpoints", havingValue = "true")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/dev/food-logs")
public class FoodLogDebugController {

    private final AuthContext auth;
    private final FoodLogRepository repo;

    @GetMapping("/{id}/effective")
    public ResponseEntity<?> effective(@PathVariable String id) {
        Long uid = auth.requireUserId();
        var log = repo.findByIdAndUserId(id, uid)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        return ResponseEntity.ok(
                log.getEffective() == null ? Map.of() : log.getEffective()
        );
    }

    /** 可選：看一些核心欄位 */
    @GetMapping("/{id}/meta")
    public ResponseEntity<?> meta(@PathVariable String id) {
        Long uid = auth.requireUserId();
        var log = repo.findByIdAndUserId(id, uid)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        return ResponseEntity.ok(Map.of(
                "foodLogId", log.getId(),
                "status", log.getStatus(),
                "provider", log.getProvider(),
                "method", log.getMethod(),
                "sha256", log.getImageSha256(),
                "objectKey", log.getImageObjectKey()
        ));
    }
}
