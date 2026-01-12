package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;

@RequiredArgsConstructor
@Service
public class FoodLogService {

    private final FoodLogRepository repo;
    private final ObjectMapper om;

    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, MultipartFile file, String requestId) throws Exception {
        ZoneId tz = parseTzOrUtc(clientTz);

        // ✅ Step1：時間全部以 serverNow 為準（反作弊）
        Instant serverNow = Instant.now();
        LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();

        // ✅ Step1：先以 SERVER_RECEIVED 作為 captured_at（Step2 再加 EXIF/DEVICE 優先序）
        Instant capturedAtUtc = serverNow;

        String sha256 = sha256Hex(file);

        // ✅ Stub 給 UI Demo：之後接 provider 再換掉
        JsonNode effective = om.readTree("""
          {
            "foodName": "Unknown food",
            "quantity": {"value": 1, "unit": "SERVING"},
            "nutrients": {"kcal": 120, "protein": 5, "fat": 4, "carbs": 16, "fiber": 2, "sugar": 6, "sodium": 180},
            "healthScore": 6,
            "confidence": 0.2
          }
        """);

        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setStatus(FoodLogStatus.DRAFT);  // Step1 先 DRAFT；Step2 才導入 PENDING/task
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");

        e.setCapturedAtUtc(capturedAtUtc);
        e.setCapturedTz(tz.getId());
        e.setCapturedLocalDate(todayLocal);
        e.setServerReceivedAtUtc(serverNow);

        e.setTimeSource(TimeSource.SERVER_RECEIVED);
        e.setTimeSuspect(false);

        e.setImageSha256(sha256);
        e.setEffective(effective);

        repo.save(e);

        return toEnvelope(e, requestId);
    }

    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));
        return toEnvelope(e, requestId);
    }

    private FoodLogEnvelope toEnvelope(FoodLogEntity e, String requestId) {
        JsonNode eff = e.getEffective();
        FoodLogEnvelope.NutritionResult nr = null;

        if (eff != null && !eff.isNull()) {
            JsonNode n = eff.get("nutrients");
            JsonNode q = eff.get("quantity");

            nr = new FoodLogEnvelope.NutritionResult(
                    textOrNull(eff, "foodName"),
                    q == null ? null : new FoodLogEnvelope.Quantity(doubleOrNull(q, "value"), textOrNull(q, "unit")),
                    n == null ? null : new FoodLogEnvelope.Nutrients(
                            doubleOrNull(n, "kcal"),
                            doubleOrNull(n, "protein"),
                            doubleOrNull(n, "fat"),
                            doubleOrNull(n, "carbs"),
                            doubleOrNull(n, "fiber"),
                            doubleOrNull(n, "sugar"),
                            doubleOrNull(n, "sodium")
                    ),
                    intOrNull(eff, "healthScore"),
                    doubleOrNull(eff, "confidence"),
                    new FoodLogEnvelope.Source(e.getMethod(), e.getProvider())
            );
        }

        return new FoodLogEnvelope(
                e.getId(),
                e.getStatus().name(),
                e.getDegradeLevel(),
                nr,
                null,
                null,
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static ZoneId parseTzOrUtc(String tz) {
        try {
            return (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception ignored) {
            return ZoneOffset.UTC;
        }
    }

    private static String sha256Hex(MultipartFile file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = file.getBytes(); // Step1 OK；之後可改成 stream 版避免吃 RAM
        byte[] dig = md.digest(bytes);
        return HexFormat.of().formatHex(dig);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }
}
