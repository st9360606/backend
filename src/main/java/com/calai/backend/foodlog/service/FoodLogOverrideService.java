package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.model.FoodLogFieldKey;
import com.calai.backend.foodlog.dto.FoodLogOverrideRequest;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogOverrideEntity;
import com.calai.backend.foodlog.repo.FoodLogOverrideRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class FoodLogOverrideService {

    private final FoodLogRepository logRepo;
    private final FoodLogOverrideRepository overrideRepo;
    private final FoodLogService foodLogService;

    @Transactional
    public FoodLogEnvelope applyOverride(Long userId, String foodLogId, FoodLogOverrideRequest req, String requestId) {
        if (req == null) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");

        FoodLogFieldKey key = FoodLogFieldKey.parse(req.fieldKey());
        if (key == null) throw new IllegalArgumentException("FIELD_KEY_INVALID");

        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");

        if (log.getStatus() == FoodLogStatus.DELETED) throw new IllegalArgumentException("FOOD_LOG_DELETED");
        if (log.getStatus() != FoodLogStatus.DRAFT) {
            // ✅ MVP：只允許 DRAFT 編輯
            throw new IllegalArgumentException("FOOD_LOG_NOT_EDITABLE");
        }

        validateNewValueOrThrow(key, req.newValue());

        JsonNode oldValue = extractOldValue(log.getEffective(), key);

        FoodLogOverrideEntity ov = FoodLogOverrideEntity.create(
                foodLogId,
                key.name(),
                oldValue,
                req.newValue(),
                "USER",
                req.reason(),
                Instant.now()
        );
        overrideRepo.save(ov);

        // ✅ patch effective
        log.applyEffectivePatch(key.name(), req.newValue());
        logRepo.save(log);

        return foodLogService.getOne(userId, foodLogId, requestId);
    }

    private static JsonNode extractOldValue(JsonNode effective, FoodLogFieldKey key) {
        if (effective == null || effective.isNull() || !effective.isObject()) return null;

        return switch (key) {
            case FOOD_NAME -> effective.get("foodName");
            case QUANTITY -> effective.get("quantity");
            case NUTRIENTS -> effective.get("nutrients");
            case HEALTH_SCORE -> effective.get("healthScore");
        };
    }

    private static void validateNewValueOrThrow(FoodLogFieldKey key, JsonNode v) {
        if (v == null || v.isNull()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");

        switch (key) {
            case FOOD_NAME -> {
                if (!v.isTextual()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                String s = v.asText().trim();
                if (s.isEmpty() || s.length() > 80) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
            }
            case HEALTH_SCORE -> {
                if (!v.isInt() && !v.isIntegralNumber()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                int n = v.asInt();
                if (n < 1 || n > 10) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
            }
            case QUANTITY -> {
                if (!v.isObject()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                JsonNode value = v.get("value");
                JsonNode unit = v.get("unit");
                if (value == null || !value.isNumber()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                if (value.asDouble() < 0d) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                if (unit == null || !unit.isTextual() || unit.asText().trim().isEmpty()) {
                    throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                }
            }
            case NUTRIENTS -> {
                if (!v.isObject()) throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                // ✅ 允許 partial，但每個有提供的欄位都必須 >= 0
                for (Map.Entry<String, JsonNode> e : v.properties()) {
                    JsonNode nv = e.getValue();
                    if (nv == null || !nv.isNumber() || nv.asDouble() < 0d) {
                        throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                    }
                }
            }
        }
    }
}
