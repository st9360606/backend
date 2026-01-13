package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;

import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
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
    private final FoodLogTaskRepository taskRepo;
    private final ObjectMapper om;

    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, MultipartFile file, String requestId) throws Exception {
        ZoneId tz = parseTzOrUtc(clientTz);

        Instant serverNow = Instant.now();
        LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();

        String sha256 = sha256Hex(file);

        // ✅ Step2：先存 PENDING，effective 先空（等 worker 填）
        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setStatus(FoodLogStatus.PENDING);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");

        e.setCapturedAtUtc(serverNow);
        e.setCapturedTz(tz.getId());
        e.setCapturedLocalDate(todayLocal);
        e.setServerReceivedAtUtc(serverNow);

        e.setTimeSource(TimeSource.SERVER_RECEIVED);
        e.setTimeSuspect(false);

        e.setImageSha256(sha256);
        e.setEffective(null);

        repo.save(e);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setFoodLogId(e.getId());
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        t.setPollAfterSec(2);
        t.setNextRetryAtUtc(null);
        taskRepo.save(t);

        return toEnvelope(e, t, requestId);
    }

    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        // ✅ 硬化：只有 PENDING/FAILED 才查 task
        FoodLogTaskEntity t = null;
        if (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED) {
            t = taskRepo.findByFoodLogId(e.getId()).orElse(null);
        }

        return toEnvelope(e, t, requestId);
    }

    private FoodLogEnvelope toEnvelope(FoodLogEntity e, FoodLogTaskEntity t, String requestId) {
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

        FoodLogEnvelope.Task task = null;
        if (t != null && (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED)) {
            task = new FoodLogEnvelope.Task(t.getId(), t.getPollAfterSec());
        }

        FoodLogEnvelope.ApiError err = null;
        if (e.getStatus() == FoodLogStatus.FAILED) {
            err = new FoodLogEnvelope.ApiError(
                    e.getLastErrorCode(),
                    "RETRY_LATER",
                    (t == null || t.getNextRetryAtUtc() == null) ? null : 2
            );
        }

        return new FoodLogEnvelope(
                e.getId(),
                e.getStatus().name(),
                e.getDegradeLevel(),
                nr,
                task,
                err,
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static ZoneId parseTzOrUtc(String tz) {
        try { return (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz); }
        catch (Exception ignored) { return ZoneOffset.UTC; }
    }

    private static String sha256Hex(MultipartFile file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = file.getBytes();
        return HexFormat.of().formatHex(md.digest(bytes));
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
