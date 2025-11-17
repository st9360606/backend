package com.calai.backend.weight.service;

import com.calai.backend.userprofile.service.UserProfileService;
import com.calai.backend.weight.dto.*;
import com.calai.backend.weight.entity.*;
import com.calai.backend.weight.repo.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class WeightService {
    private final WeightHistoryRepo history;
    private final WeightTimeseriesRepo series;
    private final UserProfileService profiles;

    private static final BigDecimal MIN_REASONABLE_KG = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_REASONABLE_KG = BigDecimal.valueOf(500);
    private static final int EARLIEST_SCAN_LIMIT = 10; // 最多往後掃 10 筆

    public WeightService(WeightHistoryRepo history, WeightTimeseriesRepo series, UserProfileService profiles) {
        this.history = history;
        this.series = series;
        this.profiles = profiles;
    }

    /** 由 header 解析 IANA 時區，失敗回 UTC（不拒絕請求） */
    public ZoneId parseZoneOrUtc(String header) {
        try { return (header == null || header.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(header.trim()); }
        catch (Exception e) { return ZoneId.of("UTC"); }
    }

    @Transactional
    public WeightItemDto log(Long uid, LogWeightRequest req, ZoneId zone, String photoUrlIfAny) {
        LocalDate logDate = (req.logDate() != null) ? req.logDate() : LocalDate.now(zone);
        BigDecimal kg = req.weightKg().setScale(1, RoundingMode.HALF_UP);

        // history upsert
        WeightHistory h = history.findByUserIdAndLogDate(uid, logDate).orElseGet(WeightHistory::new);
        h.setUserId(uid); h.setLogDate(logDate); h.setTimezone(zone.getId()); h.setWeightKg(kg);
        if (photoUrlIfAny != null) h.setPhotoUrl(photoUrlIfAny);
        history.save(h);

        // timeseries upsert
        WeightTimeseries s = series.findByUserIdAndLogDate(uid, logDate).orElseGet(WeightTimeseries::new);
        s.setUserId(uid); s.setLogDate(logDate); s.setTimezone(zone.getId()); s.setWeightKg(kg);
        series.save(s);

        return toItem(h);
    }

    public List<WeightItemDto> history7ExcludingToday(Long uid, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        var list = history.findLatestBeforeToday(uid, today, org.springframework.data.domain.PageRequest.of(0,7));
        return list.stream().map(this::toItem).toList();
    }

    /**
     * 從「全時段最早的幾筆紀錄」中，挑出第一個落在合理範圍內的起始體重。
     *
     * 規則：
     * - 依 log_date 由小到大，取最早 N 筆。
     * - 只要遇到第一個 20–500kg 的值，就用它當全時段起點。
     * - 若前 N 筆都不合理，回傳 null，交由上層 fallback。
     */
    private BigDecimal pickReasonableStartWeight(Long uid) {
        var earliestList = series.findEarliest(uid, PageRequest.of(0, EARLIEST_SCAN_LIMIT));
        if (earliestList == null || earliestList.isEmpty()) {
            return null;
        }
        for (WeightTimeseries w : earliestList) {
            BigDecimal kg = w.getWeightKg();
            if (kg == null) continue;

            if (kg.compareTo(MIN_REASONABLE_KG) >= 0 && kg.compareTo(MAX_REASONABLE_KG) <= 0) {
                return kg;
            }
        }
        return null;
    }

    public SummaryDto summary(Long uid, LocalDate start, LocalDate end) {
        var goal = profiles.getOrThrow(uid);
        BigDecimal goalKg = goal.targetWeightKg() != null
                ? BigDecimal.valueOf(goal.targetWeightKg())
                : null;

        var points = series.findRange(uid, start, end)
                .stream()
                .map(this::toItem)
                .toList();

        // current＝今日或最近一筆
        var latest = series.findLatest(uid, org.springframework.data.domain.PageRequest.of(0,1));
        BigDecimal currentKg = latest.isEmpty() ? null : latest.get(0).getWeightKg();

        // ★ 新增：從全時段 timeseries 挑選合理的起始體重
        BigDecimal startKg = pickReasonableStartWeight(uid);
        if (startKg == null && currentKg != null) {
            // fallback：前 N 筆都不合理，就用 current 當起點，避免 front-end 拿不到起始值
            startKg = currentKg;
        }

        double achieved = 0d;
        if (goalKg != null && currentKg != null && startKg != null) {
            // 以「起始體重 → 目標」的距離為分母
            double total = startKg.subtract(goalKg).abs().doubleValue();
            double now   = currentKg.subtract(goalKg).abs().doubleValue();
            achieved = (total <= 0.0001)
                    ? 1.0
                    : Math.max(0, Math.min(1, (total - now) / total));
        }

        return new SummaryDto(
                goalKg, toLbsInt(goalKg),
                currentKg, toLbsInt(currentKg),
                startKg,                      // ★ 第 5 個參數：firstWeightKgAllTimeKg
                achieved * 100.0,
                points
        );
    }

    private WeightItemDto toItem(WeightHistory w) {
        return new WeightItemDto(w.getLogDate(), w.getWeightKg(), toLbsInt(w.getWeightKg()), w.getPhotoUrl());
    }
    private WeightItemDto toItem(WeightTimeseries w) {
        return new WeightItemDto(w.getLogDate(), w.getWeightKg(), toLbsInt(w.getWeightKg()), null);
    }
    private Integer toLbsInt(BigDecimal kg) {
        if (kg == null) return null;
        return BigDecimal.valueOf(kg.doubleValue() * 2.20462262d).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
