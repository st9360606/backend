package com.calai.backend.weight.service;

import com.calai.backend.common.storage.LocalImageStorage;
import com.calai.backend.userprofile.common.Units;
import com.calai.backend.userprofile.service.UserProfileService;
import com.calai.backend.weight.dto.LogWeightRequest;
import com.calai.backend.weight.dto.SummaryDto;
import com.calai.backend.weight.dto.WeightItemDto;
import com.calai.backend.weight.entity.WeightHistory;
import com.calai.backend.weight.entity.WeightTimeseries;
import com.calai.backend.weight.repo.WeightHistoryRepo;
import com.calai.backend.weight.repo.WeightTimeseriesRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;


@Service
public class WeightService {
    private final WeightHistoryRepo history;
    private final WeightTimeseriesRepo series;
    private final UserProfileService profiles;
    private final LocalImageStorage images;
    private static final BigDecimal MIN_REASONABLE_KG = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_REASONABLE_KG = BigDecimal.valueOf(800);
    private static final BigDecimal MIN_REASONABLE_LBS = BigDecimal.valueOf(40);
    private static final BigDecimal MAX_REASONABLE_LBS = BigDecimal.valueOf(1000);
    private static final int EARLIEST_SCAN_LIMIT = 10; // 最多往後掃 10 筆

    public WeightService(
            WeightHistoryRepo history,
            WeightTimeseriesRepo series,
            UserProfileService profiles,
            LocalImageStorage images // ✅ constructor 注入
    ) {
        this.history = history;
        this.series = series;
        this.profiles = profiles;
        this.images = images;
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
     * - 只要遇到第一個 20–800kg 的值，就用它當全時段起點。
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

        BigDecimal goalKg = profile.goalWeightKg() != null
                ? BigDecimal.valueOf(profile.goalWeightKg())
                : null;

        BigDecimal goalLbs = profile.goalWeightLbs() != null
                ? BigDecimal.valueOf(profile.goalWeightLbs())
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

        // ★ 全期間起點（只看 timeseries）
        BigDecimal startAllTimeKg = pickReasonableStartWeight(uid);

        // ★ 和前端 computeWeightProgress 一樣的起點 fallback：
        // 1) 全期間起點（timeseries 第一筆）
        // 2) user_profiles.weightKg（Onboarding / Profile 當時體重）
        // 3) currentKg（最後 fallback）
        BigDecimal startKgForProgress = resolveStartKgForProgress(
                startAllTimeKg,
                profileCurrentKg,
                currentKg
        );

        double achieved = 0d;
        if (goalKg != null && currentKg != null && startKgForProgress != null) {
            achieved = computeAchievedPercent(startKgForProgress, currentKg, goalKg);
        }

        return new SummaryDto(
                goalKg,
                goalLbs,
                currentKg,
                currentLbs,
                startAllTimeKg,   // ★ 對外仍然回「全期間起點」，給前端畫圖使用
                profileCurrentKg, // ★ user_profiles 的原始體重（可做 UI fallback）
                profileCurrentLbs,
                achieved * 100.0, // ★ 0~100，前端除以 100 變成 0~1
                points
        );
    }

    /**
     * 取得「用來算進度」的起點：
     * 1) 優先使用全期間起點（timeseries 第一筆）
     * 2) 沒有 timeseries 時，退回 user_profiles.weightKg
     * 3) 再沒有就退回 currentKg
     */
    private BigDecimal resolveStartKgForProgress(
            BigDecimal startAllTimeKg,
            BigDecimal profileCurrentKg,
            BigDecimal currentKg
    ) {
        if (startAllTimeKg != null) return startAllTimeKg;
        if (profileCurrentKg != null) return profileCurrentKg;
        return currentKg;
    }

    /**
     * 與前端 computeWeightProgress 對齊的進度公式：
     *
     * 減重： progress = (start - current) / (start - goal)
     * 增重： progress = (current - start) / (goal - start)
     *
     * 並將結果夾在 0.0 ~ 1.0 之間。
     */
    private double computeAchievedPercent(
            BigDecimal startKg,
            BigDecimal currentKg,
            BigDecimal goalKg
    ) {
        double start   = startKg.doubleValue();
        double current = currentKg.doubleValue();
        double goal    = goalKg.doubleValue();

        // 起點與目標幾乎相等：直接視為 100%
        if (Math.abs(goal - start) < 1e-6) {
            return 1.0d;
        }

        final double numerator;
        final double denominator;

        if (goal < start) {
            // 減重：目標比起點低
            numerator = start - current;
            denominator = start - goal;
        } else {
            // 增重：目標比起點高或相同
            numerator = current - start;
            denominator = goal - start;
        }

        if (Math.abs(denominator) < 1e-6) {
            return 0.0d;
        }

        double raw = numerator / denominator;

        // 夾在 0~1（避免 -進度或 >100%）
        if (raw < 0.0d) raw = 0.0d;
        if (raw > 1.0d) raw = 1.0d;

        return raw;
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

    /** ✅ Controller 改呼叫這個：負責同日覆蓋與刪舊圖 */
    @Transactional
    public WeightItemDto logWithPhoto(Long uid, LogWeightRequest req, ZoneId zone, MultipartFile photo) throws IOException {
        LocalDate logDate = (req.logDate() != null) ? req.logDate() : LocalDate.now(zone);

        // 先找舊的 photoUrl（同一天）
        String oldUrl = history.findByUserIdAndLogDate(uid, logDate)
                .map(h -> h.getPhotoUrl())
                .orElse(null);

        // 先把新圖存到磁碟（避免先刪舊造成資料遺失）
        String newUrl = null;
        if (photo != null && !photo.isEmpty()) {
            validatePhoto(photo);
            String ext = detectExt(photo);
            newUrl = images.save(uid, logDate, photo, ext);
        }

        final String newUrlFinal = newUrl;

        try {
            // 走你原本的 log（DB upsert）
            WeightItemDto dto = log(
                    uid,
                    new LogWeightRequest(req.weightKg(), req.weightLbs(), logDate),
                    zone,
                    newUrlFinal
            );

            // ✅ 交易成功後：刪舊圖（同日覆蓋）
            if (newUrlFinal != null && oldUrl != null && !oldUrl.equals(newUrlFinal)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        images.deleteByUrlQuietly(oldUrl);
                    }
                    @Override public void afterCompletion(int status) {
                        // 若 rollback：把剛存的新檔刪掉（避免孤兒檔案）
                        if (status != STATUS_COMMITTED) {
                            images.deleteByUrlQuietly(newUrlFinal);
                        }
                    }
                });
            } else if (newUrlFinal != null) {
                // 沒有舊圖，但 rollback 時仍要清新檔
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            images.deleteByUrlQuietly(newUrlFinal);
                        }
                    }
                });
            }

            return dto;
        } catch (RuntimeException e) {
            // 保險：若還沒註冊 sync 或其他例外
            if (newUrlFinal != null) images.deleteByUrlQuietly(newUrlFinal);
            throw e;
        }
    }

    private void validatePhoto(MultipartFile photo) {
        if (photo.getSize() > 3 * 1024 * 1024L) {
            throw new IllegalArgumentException("photo too large");
        }
        String type = (photo.getContentType() == null) ? "" : photo.getContentType();
        boolean okType = type.equals("image/jpeg")
                || type.equals("image/jpg")
                || type.equals("image/png")
                || type.equals("image/heic")
                || type.equals("image/heif")
                || type.equals("application/octet-stream");
        if (!okType) throw new IllegalArgumentException("unsupported photo contentType=" + type);
    }

    private String detectExt(MultipartFile photo) {
        String type = (photo.getContentType() == null) ? "" : photo.getContentType();

        if (type.equals("image/png")) return "png";
        if (type.equals("image/heic")) return "heic";
        if (type.equals("image/heif")) return "heif";

        // jpeg / jpg / octet-stream → 以檔名推斷，推不到就 jpg
        String fn = photo.getOriginalFilename() == null ? "" : photo.getOriginalFilename().toLowerCase();
        if (fn.endsWith(".png")) return "png";
        if (fn.endsWith(".heic")) return "heic";
        if (fn.endsWith(".heif")) return "heif";
        return "jpg";
    }

}
