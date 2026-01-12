package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.db.FoodLogEntity;
import com.calai.backend.foodlog.db.FoodLogOverrideEntity;
import com.calai.backend.foodlog.db.FoodLogOverrideRepository;
import com.calai.backend.foodlog.db.FoodLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class FoodLogOverrideService {

    private final FoodLogRepository logRepo;
    private final FoodLogOverrideRepository overrideRepo;

    @Transactional
    public void applyOverride(UUID foodLogId, String fieldKey, JsonNode oldValue, JsonNode newValue) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);

        // 1) insert override track
        FoodLogOverrideEntity ov = FoodLogOverrideEntity.create(foodLogId, fieldKey, oldValue, newValue, "USER", null, Instant.now());
        overrideRepo.save(ov);

        // 2) update effective json (你用 fieldKey 決定寫入位置)
        log.applyEffectivePatch(fieldKey, newValue);

        logRepo.save(log);
    }
}
