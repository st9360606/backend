package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogOverrideEntity;
import com.calai.backend.foodlog.repo.FoodLogOverrideRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class FoodLogOverrideService {

    private final FoodLogRepository logRepo;
    private final FoodLogOverrideRepository overrideRepo;

    @Transactional
    public void applyOverride(String foodLogId, String fieldKey, JsonNode oldValue, JsonNode newValue) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);

        FoodLogOverrideEntity ov = FoodLogOverrideEntity.create(
                foodLogId, fieldKey, oldValue, newValue, "USER", null, Instant.now()
        );
        overrideRepo.save(ov);

        // ✅ Step4 才做真正 patch；Step2/3 先讓它能編譯與記錄
        log.applyEffectivePatch(fieldKey, newValue);

        logRepo.save(log);
    }
}
