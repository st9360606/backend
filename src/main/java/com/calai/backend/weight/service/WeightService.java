package com.calai.backend.weight.service;

import com.calai.backend.userprofile.common.Units;
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
    private static final BigDecimal MAX_REASONABLE_KG = BigDecimal.valueOf(800);
    private static final BigDecimal MIN_REASONABLE_LBS = BigDecimal.valueOf(40);
    private static final BigDecimal MAX_REASONABLE_LBS = BigDecimal.valueOf(1000);
    private static final int EARLIEST_SCAN_LIMIT = 10; // 最多往後掃 10 筆

    public WeightService(WeightHistoryRepo history, WeightTimeseriesRepo series, UserProfileService profiles) {
        this.history = history;
        this.series = series;
        this.profiles = profiles;
    }

    /**  FOR 新用戶
     * - 根據 user_profiles 的體重，在「沒有任何體重紀錄」時建立 baseline。
     * - 若已存在任一 weight_history，就不動作（idempotent）。
     * - 若 profile 沒有 weightKg，也不動作。
     * - log_date 使用當地今天（根據 zone）。
     */
    @Transactional
    public void ensureBaselineFromProfile(Long uid, ZoneId zone) {
        // 1) 若已有任何 history 記錄，直接跳過（避免舊用戶重建 baseline）
        var anyHistory = history.findAllByUserDesc(uid, PageRequest.of(0, 1));
        if (anyHistory != null && !anyHistory.isEmpty()) {
            return;
        }

        // 2) 從 profile 拿目前體重（kg / lbs）
        var profile = profiles.getOrThrow(uid);
        Double profileKg  = profile.weightKg();
        Double profileLbs = profile.weightLbs();

        // 至少要有 kg，沒有就不建 baseline
        if (profileKg == null) {
            return;
        }

        // 3) clamp + 0.1 精度處理
        //    - kg 一定來自 profile
        //    - lbs：優先用 profile，沒有就用 kg 換算
        double kgVal = Units.clamp(
                profileKg,
                MIN_REASONABLE_KG.doubleValue(),
                MAX_REASONABLE_KG.doubleValue()
        );

        double lbsVal;
        if (profileLbs != null) {
            // 用戶本來就有存 lbs：尊重使用者輸入，只做 clamp
            lbsVal = Units.clamp(
                    profileLbs,
                    MIN_REASONABLE_LBS.doubleValue(),
                    MAX_REASONABLE_LBS.doubleValue()
            );
        } else {
            // 舊資料只存 kg：用 kg 統一換算成 lbs 再 clamp
            double lbsFromKg = Units.kgToLbs1(kgVal); // 你自家的 0.1 lbs 換算規則
            lbsVal = Units.clamp(
                    lbsFromKg,
                    MIN_REASONABLE_LBS.doubleValue(),
                    MAX_REASONABLE_LBS.doubleValue()
            );
        }

        BigDecimal kg  = BigDecimal.valueOf(kgVal).setScale(1, RoundingMode.HALF_UP);
        BigDecimal lbs = BigDecimal.valueOf(lbsVal).setScale(1, RoundingMode.HALF_UP);

        LocalDate today = LocalDate.now(zone);
        String tzId = zone.getId();

        // 4) history upsert（當天那一筆，如已有同日紀錄就覆寫）
        WeightHistory h = history.findByUserIdAndLogDate(uid, today)
                .orElseGet(WeightHistory::new);
        h.setUserId(uid);
        h.setLogDate(today);
        h.setTimezone(tzId);
        h.setWeightKg(kg);
        h.setWeightLbs(lbs);
        // baseline 不會有照片
        history.save(h);

        // 5) timeseries upsert（同樣 log_date）
        WeightTimeseries s = series.findByUserIdAndLogDate(uid, today)
                .orElseGet(WeightTimeseries::new);
        s.setUserId(uid);
        s.setLogDate(today);
        s.setTimezone(tzId);
        s.setWeightKg(kg);
        s.setWeightLbs(lbs);
        series.save(s);
    }

    /** 由 header 解析 IANA 時區，失敗回 UTC（不拒絕請求） */
    public ZoneId parseZoneOrUtc(String header) {
        try { return (header == null || header.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(header.trim()); }
        catch (Exception e) { return ZoneId.of("UTC"); }
    }

    @Transactional
    public WeightItemDto log(Long uid, LogWeightRequest req, ZoneId zone, String photoUrlIfAny) {
        LocalDate logDate = (req.logDate() != null) ? req.logDate() : LocalDate.now(zone);

        BigDecimal reqKg  = req.weightKg();
        BigDecimal reqLbs = req.weightLbs();

        if (reqKg == null && reqLbs == null) {
            throw new IllegalArgumentException("Either weightKg or weightLbs must be provided");
        }

        // 先統一成 double，再用 Units 做 0.1 精度處理
        Double kgD  = (reqKg  != null) ? Units.floor(reqKg, 1)  : null;
        Double lbsD = (reqLbs != null) ? Units.floor(reqLbs, 1) : null;

        if (kgD == null && lbsD != null) {
            kgD = Units.lbsToKg1(lbsD);   // lbs -> kg (0.1kg)
        }
        if (lbsD == null && kgD != null) {
            lbsD = Units.kgToLbs1(kgD);   // kg -> lbs (0.1lbs)
        }

        if (kgD == null || lbsD == null) {
            throw new IllegalStateException("Failed to derive both kg and lbs");
        }

        BigDecimal kg  = BigDecimal.valueOf(kgD).setScale(1, RoundingMode.HALF_UP);
        BigDecimal lbs = BigDecimal.valueOf(lbsD).setScale(1, RoundingMode.HALF_UP);

        // history upsert
        WeightHistory h = history.findByUserIdAndLogDate(uid, logDate)
                .orElseGet(WeightHistory::new);
        h.setUserId(uid);
        h.setLogDate(logDate);
        h.setTimezone(zone.getId());
        h.setWeightKg(kg);
        h.setWeightLbs(lbs);
        if (photoUrlIfAny != null) {
            h.setPhotoUrl(photoUrlIfAny);  // 有新照片就覆寫
        }
        history.save(h);

        // timeseries upsert
        WeightTimeseries s = series.findByUserIdAndLogDate(uid, logDate)
                .orElseGet(WeightTimeseries::new);
        s.setUserId(uid);
        s.setLogDate(logDate);
        s.setTimezone(zone.getId());
        s.setWeightKg(kg);
        s.setWeightLbs(lbs);
        series.save(s);

        return toItem(h);
    }

    /**
     * ✅ 永遠回「這個 user 最新的 7 筆體重」，只依 logDate 由新到舊排序。
     * 不再使用 today 過濾，未來若真的有「未來日期」紀錄，也會被視為最新的一筆。
     */
    public List<WeightItemDto> history7Latest(Long uid) {
        var list = history.findAllByUserDesc(uid, PageRequest.of(0, 7));
        return list.stream()
                .map(this::toItem)
                .toList();
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
        // 先把 user_profiles 撈出來
        var profile = profiles.getOrThrow(uid);

        BigDecimal goalKg = profile.targetWeightKg() != null
                ? BigDecimal.valueOf(profile.targetWeightKg())
                : null;

        BigDecimal goalLbs = profile.targetWeightLbs() != null
                ? BigDecimal.valueOf(profile.targetWeightLbs())
                : null;

        BigDecimal profileCurrentKg = profile.weightKg() != null
                ? BigDecimal.valueOf(profile.weightKg())
                : null;

        BigDecimal profileCurrentLbs = profile.weightLbs() != null
                ? BigDecimal.valueOf(profile.weightLbs())
                : null;

        // 這個 range 的折線資料
        var points = series.findRange(uid, start, end)
                .stream()
                .map(this::toItem)
                .toList();

        // 最新一筆 timeseries（若有）
        var latestList = series.findLatest(uid, PageRequest.of(0, 1));

        BigDecimal currentKg = null;
        BigDecimal currentLbs = null;

        if (!latestList.isEmpty()) {
            // ★ 有 weight_timeseries：一律吃 DB 的 kg / lbs
            var latest = latestList.get(0);
            currentKg = latest.getWeightKg();
            currentLbs = latest.getWeightLbs();
        } else {
            // ★ 沒任何 timeseries：fallback 到 user_profiles
            currentKg = profileCurrentKg;
            if (profileCurrentLbs != null) {
                currentLbs = profileCurrentLbs;
            } else if (currentKg != null) {
                // 舊資料只存 kg 的情況：再做一次統一換算（小數一位）
                double lbs1 = Units.kgToLbs1(currentKg.doubleValue());
                currentLbs = BigDecimal.valueOf(lbs1)
                        .setScale(1, RoundingMode.HALF_UP);
            }
        }

        // 起始體重仍然掃全時段 timeseries；沒有就 fallback current
        BigDecimal startKg = pickReasonableStartWeight(uid);
        if (startKg == null && currentKg != null) {
            startKg = currentKg;
        }

        double achieved = 0d;
        if (goalKg != null && currentKg != null && startKg != null) {
            double total = startKg.subtract(goalKg).abs().doubleValue();
            double now = currentKg.subtract(goalKg).abs().doubleValue();
            achieved = (total <= 1e-4)
                    ? 1.0
                    : Math.max(0, Math.min(1, (total - now) / total));
        }

        return new SummaryDto(
                goalKg,
                goalLbs,
                currentKg,
                currentLbs,
                startKg,
                profileCurrentKg,  // ★ 新增：直接帶出 user_profiles 的 weight_kg / weight_lbs
                profileCurrentLbs,
                achieved * 100.0,
                points
        );
    }

    private WeightItemDto toItem(WeightHistory w) {
        return new WeightItemDto(
                w.getLogDate(),
                w.getWeightKg(),
                w.getWeightLbs(),    // ★ 直接用 DB 欄位
                w.getPhotoUrl()
        );
    }

    private WeightItemDto toItem(WeightTimeseries w) {
        return new WeightItemDto(
                w.getLogDate(),
                w.getWeightKg(),
                w.getWeightLbs(),    // ★ 直接用 DB 欄位
                null
        );
    }

    private Integer toLbsInt(BigDecimal kg) {
        if (kg == null) return null;
        return BigDecimal.valueOf(kg.doubleValue() * 2.20462262d)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }
}
