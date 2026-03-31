package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogOverrideRequest;
import com.calai.backend.foodlog.dto.FoodLogPortionMultiplierRequest;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogOverrideEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogFieldKey;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogOverrideRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    public FoodLogEnvelope applyOverride(
            Long userId,
            String foodLogId,
            FoodLogOverrideRequest req,
            String requestId
    ) {
        if (req == null) {
            throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
        }

        FoodLogFieldKey key = FoodLogFieldKey.parse(req.fieldKey());
        if (key == null) {
            throw new FoodLogAppException(FoodLogErrorCode.FIELD_KEY_INVALID);
        }

        FoodLogEntity log = requireEditableLog(userId, foodLogId);

        validateNewValueOrThrow(key, req.newValue());

        int currentMultiplier = log.getPortionMultiplier() == null
                ? 1
                : Math.max(1, log.getPortionMultiplier());

        JsonNode oldValue = extractOldValue(log.getEffective(), key);

        overrideRepo.save(FoodLogOverrideEntity.create(
                foodLogId,
                key.name(),
                oldValue,
                req.newValue(),
                "USER",
                req.reason(),
                Instant.now()
        ));

        ObjectNode baseRoot = ensureBaseEffective(log, currentMultiplier);

        // effective：直接套目前畫面值
        log.applyEffectivePatch(key.name(), req.newValue());

        // baseEffective：轉回 1x canonical 再套
        JsonNode normalizedBaseValue = normalizeOverrideValueForBase(
                key,
                req.newValue(),
                currentMultiplier
        );
        applyPatch(baseRoot, key, normalizedBaseValue);
        log.setBaseEffective(baseRoot);

        logRepo.save(log);

        return foodLogService.getOne(userId, foodLogId, requestId);
    }

    @Transactional
    public FoodLogEnvelope applyPortionMultiplier(
            Long userId,
            String foodLogId,
            FoodLogPortionMultiplierRequest req,
            String requestId
    ) {
        if (req == null || req.multiplier() == null || req.multiplier() < 1) {
            throw new FoodLogAppException(FoodLogErrorCode.BAD_REQUEST);
        }

        FoodLogEntity log = requireEditableLog(userId, foodLogId);

        int targetMultiplier = req.multiplier();
        int currentMultiplier = log.getPortionMultiplier() == null
                ? 1
                : Math.max(1, log.getPortionMultiplier());

        if (targetMultiplier == currentMultiplier) {
            return foodLogService.getOne(userId, foodLogId, requestId);
        }

        JsonNode baseEffective = log.getBaseEffective();
        if (baseEffective == null || baseEffective.isNull() || !baseEffective.isObject()) {
            // 關鍵：不要再直接 currentEffective.deepCopy()
            ObjectNode derivedBase = ensureBaseEffective(log, currentMultiplier);
            log.setBaseEffective(derivedBase);
            baseEffective = derivedBase;
            log.setPortionMultiplier(currentMultiplier);
        }

        ObjectNode scaledEffective = buildScaledEffective(baseEffective, targetMultiplier);

        Instant now = Instant.now();
        String reason = req.reason() != null && !req.reason().isBlank()
                ? req.reason()
                : "RECENT_UPLOAD_MULTIPLIER_X" + targetMultiplier;

        overrideRepo.save(FoodLogOverrideEntity.create(
                foodLogId,
                "PORTION_MULTIPLIER",
                JsonNodeFactory.instance.numberNode(currentMultiplier),
                JsonNodeFactory.instance.numberNode(targetMultiplier),
                "USER",
                reason,
                now
        ));

        log.setEffective(scaledEffective);
        log.setPortionMultiplier(targetMultiplier);
        logRepo.save(log);

        return foodLogService.getOne(userId, foodLogId, requestId);
    }

    private FoodLogEntity requireEditableLog(Long userId, String foodLogId) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        if (!userId.equals(log.getUserId())) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_DELETED);
        }

        if (log.getStatus() != FoodLogStatus.DRAFT && log.getStatus() != FoodLogStatus.SAVED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_EDITABLE);
        }

        return log;
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

    private static ObjectNode buildScaledEffective(JsonNode baseEffective, int multiplier) {
        if (baseEffective == null || !baseEffective.isObject()) {
            throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
        }

        ObjectNode out = ((ObjectNode) baseEffective).deepCopy();

        JsonNode nutrients = out.get("nutrients");
        if (nutrients != null && nutrients.isObject()) {
            ObjectNode nutrientsObj = (ObjectNode) nutrients;
            ObjectNode scaledNutrients = JsonNodeFactory.instance.objectNode();

            putScaledNumber(scaledNutrients, nutrientsObj, "kcal", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "protein", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "fat", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "carbs", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "fiber", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "sugar", multiplier);
            putScaledNumber(scaledNutrients, nutrientsObj, "sodium", multiplier);

            out.set("nutrients", FoodLogEntity.orderNutrients(scaledNutrients));
        }

        JsonNode quantity = out.get("quantity");
        if (quantity != null && quantity.isObject()) {
            JsonNode value = quantity.get("value");
            JsonNode unit = quantity.get("unit");

            if (value != null && value.isNumber() && unit != null && unit.isTextual()) {
                ObjectNode scaledQuantity = JsonNodeFactory.instance.objectNode();
                scaledQuantity.put("value", value.asDouble() * multiplier);
                scaledQuantity.put("unit", unit.asText().trim());
                out.set("quantity", scaledQuantity);
            }
        }

        return out;
    }

    private static void putScaledNumber(
            ObjectNode target,
            ObjectNode source,
            String field,
            int multiplier
    ) {
        JsonNode value = source.get(field);
        if (value != null && value.isNumber()) {
            target.put(field, value.asDouble() * multiplier);
        }
    }

    private static void validateNewValueOrThrow(FoodLogFieldKey key, JsonNode v) {
        if (v == null || v.isNull()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);

        switch (key) {
            case FOOD_NAME -> {
                if (!v.isTextual()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                String s = v.asText().trim();
                if (s.isEmpty() || s.length() > 80) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
            }
            case HEALTH_SCORE -> {
                if (!v.isInt() && !v.isIntegralNumber()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                int n = v.asInt();
                if (n < 0 || n > 10) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
            }
            case QUANTITY -> {
                if (!v.isObject()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                JsonNode value = v.get("value");
                JsonNode unit = v.get("unit");
                if (value == null || !value.isNumber()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                if (value.asDouble() < 0d) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                if (unit == null || !unit.isTextual() || unit.asText().trim().isEmpty()) {
                    throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                }
            }
            case NUTRIENTS -> {
                if (!v.isObject()) throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);

                ObjectNode nutrientsObj = (ObjectNode) v;
                for (Map.Entry<String, JsonNode> entry : nutrientsObj.properties()) {
                    JsonNode nv = entry.getValue();
                    if (nv == null || !nv.isNumber() || nv.asDouble() < 0d) {
                        throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
                    }
                }
            }
        }
    }

    private static ObjectNode ensureBaseEffective(FoodLogEntity log, int currentMultiplier) {
        JsonNode existingBase = log.getBaseEffective();
        if (existingBase != null && existingBase.isObject()) {
            ObjectNode copied = ((ObjectNode) existingBase).deepCopy();

            JsonNode nutrients = copied.get("nutrients");
            if (nutrients != null && nutrients.isObject()) {
                copied.set("nutrients", FoodLogEntity.orderNutrients((ObjectNode) nutrients));
            }

            return copied;
        }

        JsonNode currentEffective = log.getEffective();
        if (currentEffective == null || currentEffective.isNull() || !currentEffective.isObject()) {
            throw new FoodLogAppException(FoodLogErrorCode.OVERRIDE_VALUE_INVALID);
        }

        ObjectNode derived = deriveBaseEffective((ObjectNode) currentEffective, currentMultiplier);
        log.setBaseEffective(derived);
        return derived.deepCopy();
    }

    private static ObjectNode deriveBaseEffective(ObjectNode currentEffective, int currentMultiplier) {
        ObjectNode out = currentEffective.deepCopy();
        int safeMultiplier = Math.max(1, currentMultiplier);

        JsonNode nutrients = out.get("nutrients");
        if (nutrients != null && nutrients.isObject()) {
            ObjectNode normalized = (safeMultiplier == 1)
                    ? ((ObjectNode) nutrients).deepCopy()
                    : normalizeNutrientsNode((ObjectNode) nutrients, safeMultiplier);

            out.set("nutrients", FoodLogEntity.orderNutrients(normalized));
        }

        JsonNode quantity = out.get("quantity");
        if (quantity != null && quantity.isObject()) {
            if (safeMultiplier != 1) {
                out.set("quantity", normalizeQuantityNode((ObjectNode) quantity, safeMultiplier));
            }
        }

        return out;
    }

    private static JsonNode normalizeOverrideValueForBase(
            FoodLogFieldKey key,
            JsonNode newValue,
            int currentMultiplier
    ) {
        if (newValue == null || newValue.isNull()) {
            return newValue;
        }

        int safeMultiplier = Math.max(1, currentMultiplier);
        if (safeMultiplier == 1) {
            if (key == FoodLogFieldKey.NUTRIENTS && newValue.isObject()) {
                return FoodLogEntity.orderNutrients((ObjectNode) newValue.deepCopy());
            }
            return newValue.deepCopy();
        }

        return switch (key) {
            case FOOD_NAME, HEALTH_SCORE -> newValue.deepCopy();
            case QUANTITY -> normalizeQuantityNode((ObjectNode) newValue, safeMultiplier);
            case NUTRIENTS -> normalizeNutrientsNode((ObjectNode) newValue, safeMultiplier);
        };
    }

    private static ObjectNode normalizeQuantityNode(ObjectNode quantityNode, int multiplier) {
        ObjectNode out = quantityNode.deepCopy();

        JsonNode value = quantityNode.get("value");
        if (value != null && value.isNumber()) {
            out.put("value", value.asDouble() / multiplier);
        }

        JsonNode unit = quantityNode.get("unit");
        if (unit != null && unit.isTextual()) {
            out.put("unit", unit.asText().trim());
        }

        return out;
    }

    private static ObjectNode normalizeNutrientsNode(ObjectNode nutrientsNode, int multiplier) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();

        putNormalizedNumber(out, nutrientsNode, "kcal", multiplier);
        putNormalizedNumber(out, nutrientsNode, "protein", multiplier);
        putNormalizedNumber(out, nutrientsNode, "fat", multiplier);
        putNormalizedNumber(out, nutrientsNode, "carbs", multiplier);
        putNormalizedNumber(out, nutrientsNode, "fiber", multiplier);
        putNormalizedNumber(out, nutrientsNode, "sugar", multiplier);
        putNormalizedNumber(out, nutrientsNode, "sodium", multiplier);

        return FoodLogEntity.orderNutrients(out);
    }

    private static void putNormalizedNumber(
            ObjectNode target,
            ObjectNode source,
            String field,
            int multiplier
    ) {
        JsonNode value = source.get(field);
        if (value != null && value.isNumber()) {
            target.put(field, value.asDouble() / multiplier);
        }
    }

    private static void applyPatch(ObjectNode root, FoodLogFieldKey key, JsonNode newValue) {
        switch (key) {
            case FOOD_NAME -> {
                if (newValue == null || newValue.isNull()) root.remove("foodName");
                else root.set("foodName", newValue.deepCopy());
            }
            case QUANTITY -> {
                if (newValue == null || newValue.isNull()) root.remove("quantity");
                else root.set("quantity", newValue.deepCopy());
            }
            case HEALTH_SCORE -> {
                if (newValue == null || newValue.isNull()) root.remove("healthScore");
                else root.set("healthScore", newValue.deepCopy());
            }
            case NUTRIENTS -> {
                if (newValue == null || newValue.isNull()) {
                    root.remove("nutrients");
                } else {
                    ObjectNode merged;
                    JsonNode existing = root.get("nutrients");
                    if (existing != null && existing.isObject()) {
                        merged = ((ObjectNode) existing).deepCopy();
                    } else {
                        merged = JsonNodeFactory.instance.objectNode();
                    }

                    ObjectNode patch = (ObjectNode) newValue;
                    for (Map.Entry<String, JsonNode> entry : patch.properties()) {
                        merged.set(entry.getKey(), entry.getValue());
                    }

                    root.set("nutrients", FoodLogEntity.orderNutrients(merged));
                }
            }
        }
    }
}
