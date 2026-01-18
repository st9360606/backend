package com.calai.backend.users.profile.service;

import com.calai.backend.users.user.repo.UserRepo;
import com.calai.backend.users.auto_generate_goals.dto.AutoGoalsRequest;
import com.calai.backend.users.auto_generate_goals.exception.MissingFieldsException;
import com.calai.backend.users.profile.common.*;
import com.calai.backend.users.profile.dto.UpdateGoalWeightRequest;
import com.calai.backend.users.profile.dto.UpsertProfileRequest;
import com.calai.backend.users.profile.dto.UserProfileDto;
import com.calai.backend.users.profile.entity.UserProfile;
import com.calai.backend.users.profile.repo.UserProfileRepository;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.weight.entity.WeightHistory;
import com.calai.backend.weight.entity.WeightTimeseries;
import com.calai.backend.weight.repo.WeightHistoryRepo;
import com.calai.backend.weight.repo.WeightTimeseriesRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class UserProfileService {

    private final UserProfileRepository repo;
    private final UserRepo users;
    private final WeightTimeseriesRepo weightSeries;

    // ✅ 新增：讓 commitAutoGoals 可寫入 weight_history
    private final WeightHistoryRepo weightHistory;

    private static final double MIN_HEIGHT_CM = 80.0d;
    private static final double MAX_HEIGHT_CM = 300.0d;
    private static final double MIN_WEIGHT_KG = 20.0d;
    private static final double MAX_WEIGHT_KG = 800.0d;
    private static final double MIN_WEIGHT_LBS = 40.0d;
    private static final double MAX_WEIGHT_LBS = 1000.0d;

    private static final int DEFAULT_DAILY_STEP_GOAL = 10000;

    private static final int MAX_WATER_ML_MALE = 3700;
    private static final int MAX_WATER_ML_FEMALE = 2700;

    // ✅ 手動水量上限（對齊前端 VM/Repo）
    private static final int MAX_WATER_GOAL_ML = 20000;

    private static final int DEFAULT_DAILY_WORKOUT_GOAL_KCAL = 450;
    private static final int MAX_DAILY_WORKOUT_GOAL_KCAL = 5000;

    // ✅ constructor 增加 WeightHistoryRepo
    public UserProfileService(
            UserProfileRepository repo,
            UserRepo users,
            WeightTimeseriesRepo weightSeries,
            WeightHistoryRepo weightHistory
    ) {
        this.repo = repo;
        this.users = users;
        this.weightSeries = weightSeries;
        this.weightHistory = weightHistory;
    }

    private static int clampInt(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /**
     * ✅ 新用戶插入不會炸：補齊 NOT NULL 預設值
     * 只做「補 null」，不要做「推導值永遠覆寫」
     */
    private static void applyNotNullDefaults(UserProfile p) {
        if (p.getDailyStepGoal() == null) p.setDailyStepGoal(DEFAULT_DAILY_STEP_GOAL);
        if (p.getUnitPreference() == null || p.getUnitPreference().isBlank()) p.setUnitPreference("KG");

        if (p.getKcal() == null) p.setKcal(0);
        if (p.getCarbsG() == null) p.setCarbsG(0);
        if (p.getProteinG() == null) p.setProteinG(0);
        if (p.getFatG() == null) p.setFatG(0);

        // ✅ 這三個欄位確保 NOT NULL；推導/同步交給 refreshNutritionTargetsFromKcal()
        if (p.getFiberG() == null) p.setFiberG(NutritionTargets.DEFAULT_FIBER_G);
        if (p.getSodiumMg() == null) p.setSodiumMg(NutritionTargets.DEFAULT_SODIUM_MG);
        if (p.getSugarG() == null) {
            int kcal = Math.max(0, p.getKcal());
            p.setSugarG(NutritionTargets.SugarMaxG10(kcal));
        }

        if (p.getWaterMl() == null) p.setWaterMl(0);
        if (p.getWaterMode() == null) p.setWaterMode(WaterMode.AUTO);
        if (p.getDailyWorkoutGoalKcal() == null) p.setDailyWorkoutGoalKcal(DEFAULT_DAILY_WORKOUT_GOAL_KCAL);
        if (p.getBmi() == null) p.setBmi(0.0);
        if (p.getBmiClass() == null || p.getBmiClass().isBlank()) p.setBmiClass("UNKNOWN");

        if (p.getCalcVersion() == null || p.getCalcVersion().isBlank()) p.setCalcVersion("healthcalc_v1");
        if (p.getPlanMode() == null) p.setPlanMode(PlanMode.AUTO);
    }

    /**
     * ✅ 核心修正：任何時候只要「kcal 可能改變」，就用最新 kcal 同步 sugar
     * sugar = floor(kcal * 0.10 / 4) = floor(kcal / 40)
     * 設計決策：sugar 視為推導值（不提供手動編輯），因此每次存檔前都會覆寫成正確值。
     */
    static void refreshNutritionTargetsFromKcal(UserProfile p) {
        if (p == null) return;

        int kcal = (p.getKcal() == null) ? 0 : Math.max(0, p.getKcal());

        if (p.getFiberG() == null) p.setFiberG(NutritionTargets.DEFAULT_FIBER_G);
        if (p.getSodiumMg() == null) p.setSodiumMg(NutritionTargets.DEFAULT_SODIUM_MG);

        // ✅ AUTO：同步；MANUAL：不覆寫（除非 null）
        PlanMode mode = p.getPlanMode();
        boolean isAuto = (mode == null) || (mode == PlanMode.AUTO);

        if (isAuto) {
            p.setFiberG(NutritionTargets.DEFAULT_FIBER_G);
            p.setSodiumMg(NutritionTargets.DEFAULT_SODIUM_MG);
            p.setSugarG(NutritionTargets.SugarMaxG10(kcal));
        } else {
            if (p.getFiberG() == null) p.setFiberG(NutritionTargets.DEFAULT_FIBER_G);
            if (p.getSodiumMg() == null) p.setSodiumMg(NutritionTargets.DEFAULT_SODIUM_MG);
            if (p.getSugarG() == null) p.setSugarG(NutritionTargets.SugarMaxG10(kcal));
        }
    }

    /** 首次登入或缺資料時：確保至少有一筆最小 Profile */
    @Transactional
    public UserProfile ensureDefault(User u) {
        return repo.findByUserId(u.getId()).orElseGet(() -> {
            var np = new UserProfile();
            np.setUser(u);
            applyNotNullDefaults(np);
            refreshNutritionTargetsFromKcal(np);
            return repo.save(np);
        });
    }

    @Transactional
    public UserProfile ensureDefault(Long userId) {
        var u = users.findById(userId).orElseThrow();
        return ensureDefault(u);
    }

    @Transactional(readOnly = true)
    public UserProfileDto getOrThrow(Long userId) {
        var p = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("PROFILE_NOT_FOUND"));
        return toDto(p);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long userId) {
        return repo.existsByUserId(userId);
    }

    // =========================
    // ✅ Upsert：新增 allowPlanRecalc（只有 onboarding = true 才會重算宏量）
    // =========================

    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r) {
        return upsertInternal(userId, r, null, false);
    }

    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r, String tz) {
        return upsertInternal(userId, r, tz, false);
    }

    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r, String tz, boolean allowPlanRecalc) {
        return upsertInternal(userId, r, tz, allowPlanRecalc);
    }

    @Transactional
    protected UserProfileDto upsertInternal(Long userId, UpsertProfileRequest r, String tz, boolean allowPlanRecalc) {
        var u = users.findById(userId).orElseThrow();
        var p = repo.findByUserId(userId).orElseGet(() -> {
            var np = new UserProfile();
            np.setUser(u);
            applyNotNullDefaults(np);
            refreshNutritionTargetsFromKcal(np);
            return np;
        });

        // ---------- 身高 ----------
        if (r.heightFeet() != null && r.heightInches() != null) {
            short ft = r.heightFeet();
            short in = r.heightInches();

            p.setHeightFeet(ft);
            p.setHeightInches(in);

            Double cm = Units.feetInchesToCm(ft, in);
            cm = Units.clamp(cm, MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            p.setHeightCm(cm);

        } else if (r.heightCm() != null) {
            Double cm = Units.clamp(r.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            p.setHeightCm(cm);
            p.setHeightFeet(null);
            p.setHeightInches(null);
        }

        // ---------- 現在體重（只更新 user_profiles 欄位，不觸發宏量重算） ----------
        {
            Double reqKg  = r.weightKg();
            Double reqLbs = r.weightLbs();

            if (reqKg != null || reqLbs != null) {
                Double kgToSave;
                Double lbsToSave;

                if (reqKg != null && reqLbs != null) {
                    kgToSave  = Units.clamp(reqKg,  MIN_WEIGHT_KG,  MAX_WEIGHT_KG);
                    lbsToSave = Units.clamp(reqLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else if (reqKg != null) {
                    kgToSave  = Units.clamp(reqKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                    lbsToSave = Units.kgToLbs1(kgToSave);
                    lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else {
                    lbsToSave = Units.clamp(reqLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                    kgToSave  = Units.lbsToKg1(lbsToSave);
                    kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                }

                p.setWeightKg(kgToSave);
                p.setWeightLbs(lbsToSave);
            }
        }

        // ---------- 目標體重 ----------
        {
            Double reqGoalKg  = r.goalWeightKg();
            Double reqGoalLbs = r.goalWeightLbs();

            if (reqGoalKg != null || reqGoalLbs != null) {
                Double kgToSave;
                Double lbsToSave;

                if (reqGoalKg != null && reqGoalLbs != null) {
                    kgToSave  = Units.clamp(reqGoalKg,  MIN_WEIGHT_KG,  MAX_WEIGHT_KG);
                    lbsToSave = Units.clamp(reqGoalLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else if (reqGoalKg != null) {
                    kgToSave  = Units.clamp(reqGoalKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                    lbsToSave = Units.kgToLbs1(kgToSave);
                    lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else {
                    lbsToSave = Units.clamp(reqGoalLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                    kgToSave  = Units.lbsToKg1(lbsToSave);
                    kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                }

                p.setGoalWeightKg(kgToSave);
                p.setGoalWeightLbs(lbsToSave);
            }
        }

        // ---------- 其他欄位 ----------
        if (r.gender() != null)         p.setGender(r.gender());
        if (r.age() != null)            p.setAge(r.age());
        if (r.exerciseLevel() != null)  p.setExerciseLevel(r.exerciseLevel());
        if (r.goal() != null)           p.setGoal(r.goal());
        if (r.referralSource() != null) p.setReferralSource(r.referralSource());
        if (r.locale() != null)         p.setLocale(r.locale());

        if (r.dailyStepGoal() != null) {
            int goal = clampInt(r.dailyStepGoal(), 200000);
            p.setDailyStepGoal(goal);
        }

        if (r.unitPreference() != null && !r.unitPreference().isBlank()) {
            String unit = r.unitPreference().trim().toUpperCase();
            if (!"KG".equals(unit) && !"LBS".equals(unit)) {
                throw new IllegalArgumentException("unitPreference must be KG or LBS");
            }
            p.setUnitPreference(unit);
        }

        if (r.workoutsPerWeek() != null) {
            int w = clampInt(r.workoutsPerWeek(), 7);
            p.setWorkoutsPerWeek(w);
        } else if (r.exerciseLevel() != null && p.getWorkoutsPerWeek() == null) {
            Integer w = exerciseLevelToBucket(r.exerciseLevel());
            if (w != null) p.setWorkoutsPerWeek(w);
        }

        if (tz != null && !tz.isBlank()) {
            p.setTimezone(tz.trim());
        }

        if (r.dailyWorkoutGoalKcal() != null) {
            int v = clampInt(r.dailyWorkoutGoalKcal(), MAX_DAILY_WORKOUT_GOAL_KCAL); // 0..5000
            p.setDailyWorkoutGoalKcal(v);
        }

        // ---------- 每日飲水目標（手動）----------
        // ✅ 只要 request 有帶 waterMl，就視為使用者手動設定：切 MANUAL，避免被 AUTO 重算覆寫
        if (r.waterMl() != null) {
            int ml = clampInt(r.waterMl(), MAX_WATER_GOAL_ML); // 0..20000
            p.setWaterMl(ml);
            p.setWaterMode(WaterMode.MANUAL);
        }

        // 先補齊 null，避免後面計算/存檔炸
        applyNotNullDefaults(p);

        // =========================================================
        // ✅ 新規則：
        // - 一般更新：不重算 kcal/P/C/F（不管 AUTO/MANUAL）
        // - 只有 onboarding（allowPlanRecalc=true）且 planMode==AUTO 才重算宏量
        // - BMI/BMI_CLASS 永遠更新（體重優先 timeseries 最新） ，除非重onboarding 進來才用帶進來的資料去算
        // =========================================================
        if (allowPlanRecalc) {
            // ✅ 1) onboarding 視為「同意用 AUTO 方案覆蓋」
            p.setPlanMode(PlanMode.AUTO);

            // ✅ 2) onboarding 時強制切 AUTO
            p.setWaterMode(WaterMode.AUTO);

            // ✅ 3) 重算 kcal/P/C/F
            recalcMacrosOnlyIfPossible(p);
        }

        refreshNutritionTargetsFromKcal(p);

        recalcBmiFromProfile(p);

        recalcWaterIfAutoFromProfile(p);

        var saved = repo.save(p);
        return toDto(saved);
    }

    private static void recalcBmiFromProfile(UserProfile p) {
        if (p == null) return;

        Double heightCm = p.getHeightCm();
        Double weightKg = p.getWeightKg();
        if (heightCm == null || weightKg == null) return;

        double bmiRaw = PlanCalculator.bmi(weightKg, heightCm);
        double bmi1 = Math.round(bmiRaw * 10.0) / 10.0;

        var cls = PlanCalculator.classifyBmi(bmiRaw);
        p.setBmi(bmi1);
        p.setBmiClass(cls.name().toUpperCase());
    }

    private void recalcWaterIfAutoFromProfile(UserProfile p) {
        if (p == null) return;
        if (p.getWaterMode() != WaterMode.AUTO) return;

        Double kg = p.getWeightKg();
        if (kg == null) return;

        int water = calcAutoWaterMl(kg, p.getGender());
        p.setWaterMl(water);
    }

    /** ✅ 只重算 kcal/P/C/F（不碰 BMI），避免覆寫 BMI 規則 */
    private static void recalcMacrosOnlyIfPossible(UserProfile p) {
        if (!hasBaseForPlan(p)) return;

        var in = new PlanCalculator.Inputs(
                parseGender(p.getGender()),
                p.getAge(),
                p.getHeightCm().floatValue(),
                p.getWeightKg().floatValue(),
                safeWorkouts(p.getWorkoutsPerWeek())
        );

        var split = PlanCalculator.splitForGoalKey(p.getGoal());
        var plan = PlanCalculator.macroPlanBySplit(in, split, null);

        p.setKcal(plan.kcal());
        p.setCarbsG(plan.carbsGrams());
        p.setProteinG(plan.proteinGrams());
        p.setFatG(plan.fatGrams());

        p.setCalcVersion("healthcalc_v1");
    }

    /** ✅ BMI 一律用：最新 timeseries 體重 > profile.weightKg */
    private void recalcBmiFromLatestTimeseriesOrProfile(UserProfile p, Long fallbackUserId) {
        Double heightCm = p.getHeightCm();
        if (heightCm == null) return;

        Long uid = (p.getUserId() != null) ? p.getUserId() : fallbackUserId;
        Double weightKg = resolveWeightKgForBmi(uid, p.getWeightKg());
        if (weightKg == null) return;

        double bmiRaw = PlanCalculator.bmi(weightKg, heightCm);
        double bmi1 = Math.round(bmiRaw * 10.0) / 10.0;

        var cls = PlanCalculator.classifyBmi(bmiRaw);
        p.setBmi(bmi1);
        p.setBmiClass(cls.name().toUpperCase());
    }

    private Double resolveWeightKgForBmi(Long userId, Double profileFallbackKg) {
        if (userId == null) return profileFallbackKg;
        return weightSeries.findLatest(userId, PageRequest.of(0, 1))
                .stream()
                .map(WeightTimeseries::getWeightKg)
                .filter(Objects::nonNull)
                .findFirst()
                .map(BigDecimal::doubleValue)
                .orElse(profileFallbackKg);
    }

    // =========================
    // ✅ 給 WeightService 呼叫：只要 timeseries 更新，就同步 BMI/BMI_CLASS
    // =========================
    @Transactional
    public void refreshBmiFromLatestWeightIfPossible(Long userId) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));
        applyNotNullDefaults(p);

        // ✅ 保險：存檔前同步 sugar（即使此 API 不改 kcal，也避免舊資料有 null/髒值）
        refreshNutritionTargetsFromKcal(p);

        // BMI 需要身高
        if (p.getHeightCm() == null) return;

        // 體重來源：timeseries 最新 > profile
        recalcBmiFromLatestTimeseriesOrProfile(p, userId);
        repo.save(p);
    }

    // =========================
    // 目標體重（原樣保留，但存檔前同步 sugar）
    // =========================
    @Transactional
    public UserProfileDto updateGoalWeight(Long userId, UpdateGoalWeightRequest r) {
        if (r == null || r.value() == null || r.unit() == null) {
            throw new IllegalArgumentException("value and unit are required");
        }

        var p = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("PROFILE_NOT_FOUND"));

        String unit = r.unit().trim().toUpperCase();
        Double kgToSave;
        Double lbsToSave;

        if ("KG".equals(unit)) {
            kgToSave  = Units.clamp(r.value(), MIN_WEIGHT_KG, MAX_WEIGHT_KG);
            lbsToSave = Units.kgToLbs1(kgToSave);
            lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
        } else if ("LBS".equals(unit)) {
            lbsToSave = Units.clamp(r.value(), MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            kgToSave  = Units.lbsToKg1(lbsToSave);
            kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
        } else {
            throw new IllegalArgumentException("unit must be KG or LBS");
        }

        p.setGoalWeightKg(kgToSave);
        p.setGoalWeightLbs(lbsToSave);

        applyNotNullDefaults(p);
        refreshNutritionTargetsFromKcal(p);

        var saved = repo.save(p);
        return toDto(saved);
    }

    @Transactional
    public void refreshAutoWaterIfEnabled(Long userId) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));
        applyNotNullDefaults(p);

        if (p.getWaterMode() != WaterMode.AUTO) return;

        Double latestKg = weightSeries.findLatest(userId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(w -> w.getWeightKg() == null ? null : w.getWeightKg().doubleValue())
                .orElse(null);

        if (latestKg == null) latestKg = p.getWeightKg();
        if (latestKg == null) return;

        int water = calcAutoWaterMl(latestKg, p.getGender());
        p.setWaterMl(water);

        refreshNutritionTargetsFromKcal(p);
        repo.save(p);
    }

    @Transactional
    public void setManualPlan(Long userId, int kcal, int proteinG, int carbsG, int fatG) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));

        p.setKcal(Math.max(0, kcal));
        p.setProteinG(Math.max(0, proteinG));
        p.setCarbsG(Math.max(0, carbsG));
        p.setFatG(Math.max(0, fatG));
        p.setPlanMode(PlanMode.MANUAL);
        applyNotNullDefaults(p);

        // ✅ 關鍵：手動改 kcal 後也必須同步 sugar
        refreshNutritionTargetsFromKcal(p);
        p.setSugarG(NutritionTargets.SugarMaxG10(Math.max(0, p.getKcal())));

        // ✅ 可選：順手把 BMI 也更新（體重以 timeseries 最新為準）
        recalcBmiFromLatestTimeseriesOrProfile(p, userId);

        repo.save(p);
    }

    @Transactional
    public void setPlanMode(Long userId, PlanMode mode) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));

        if (mode == null) mode = PlanMode.AUTO;
        p.setPlanMode(mode);

        // ✅ 新規則：切回 AUTO 不重算宏量（只有 onboarding 才能重算）
        // 但 BMI 可更新（不影響 kcal/P/C/F）
        applyNotNullDefaults(p);
        refreshNutritionTargetsFromKcal(p);

        recalcBmiFromLatestTimeseriesOrProfile(p, userId);

        repo.save(p);
    }

    @Transactional
    public void setManualWater(Long userId, int waterMl) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));
        p.setWaterMl(Math.max(0, waterMl));
        p.setWaterMode(WaterMode.MANUAL);

        applyNotNullDefaults(p);
        refreshNutritionTargetsFromKcal(p);

        repo.save(p);
    }

    @Transactional
    public void setWaterMode(Long userId, WaterMode mode) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));
        if (mode == null) mode = WaterMode.AUTO;
        p.setWaterMode(mode);

        if (mode == WaterMode.AUTO) {
            recalcWaterIfAuto(p); // 立刻算一次
        }

        applyNotNullDefaults(p);
        refreshNutritionTargetsFromKcal(p);

        repo.save(p);
    }

    private static Integer exerciseLevelToBucket(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "sedentary" -> 0;
            case "light" -> 2;
            case "moderate" -> 4;
            case "active" -> 6;
            case "very_active", "very-active" -> 7;
            default -> null;
        };
    }

    // ✅ workoutsPerWeek -> exerciseLevel key（與 exerciseLevelToBucket() 相容）
    private static String workoutsPerWeekToExerciseLevelKey(Integer workoutsPerWeek) {
        if (workoutsPerWeek == null) return null;
        int w = safeWorkouts(workoutsPerWeek);
        if (w <= 0) return "sedentary";
        if (w <= 2) return "light";
        if (w <= 4) return "moderate";
        if (w <= 6) return "active";
        return "very_active"; // 7
    }

    private void recalcWaterIfAuto(UserProfile p) {
        if (p.getWaterMode() != WaterMode.AUTO) return;
        Long uid = p.getUserId();
        if (uid == null && p.getUser() != null) uid = p.getUser().getId();

        Double kg = null;
        if (uid != null) {
            kg = weightSeries.findLatest(uid, PageRequest.of(0, 1))
                    .stream()
                    .findFirst()
                    .map(x -> x.getWeightKg() == null ? null : x.getWeightKg().doubleValue())
                    .orElse(null);
        }
        if (kg == null) kg = p.getWeightKg();
        if (kg == null) return;

        int water = calcAutoWaterMl(kg, p.getGender());
        p.setWaterMl(water);
    }

    private static boolean hasBaseForPlan(UserProfile p) {
        return p.getGender() != null
                && p.getAge() != null
                && p.getHeightCm() != null
                && p.getWeightKg() != null;
    }

    private static int safeWorkouts(Integer w) {
        if (w == null) return 0;
        if (w < 0) return 0;
        if (w > 7) return 7;
        return w;
    }

    private static PlanCalculator.Gender parseGender(String raw) {
        if (raw == null) return PlanCalculator.Gender.Female; // 保守 + 對齊前端 else Female
        String s = raw.trim().toUpperCase();
        if ("MALE".equals(s) || "M".equals(s)) return PlanCalculator.Gender.Male;
        return PlanCalculator.Gender.Female;
    }

    /** ✅ 只認 "MALE"/"M" 為男性；其他（含 null/OTHER/FEMALE）一律視為女性（較保守） */
    private static boolean isMaleForWaterCap(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toUpperCase();
        return "MALE".equals(s) || "M".equals(s);
    }

    private static int waterCapMlByGender(String genderRaw) {
        return isMaleForWaterCap(genderRaw) ? MAX_WATER_ML_MALE : MAX_WATER_ML_FEMALE;
    }

    /** ✅ AUTO 水量：round(kg*35) 並套性別上限 */
    static int calcAutoWaterMl(double weightKg, String genderRaw) {
        int base = (int) Math.round(weightKg * 35.0d);
        if (base < 0) base = 0;
        int cap = waterCapMlByGender(genderRaw);
        return Math.min(base, cap);
    }

    private static UserProfileDto toDto(UserProfile p) {
        return new UserProfileDto(
                p.getGender(),
                p.getAge(),
                p.getHeightCm(),
                p.getHeightFeet(),
                p.getHeightInches(),
                p.getWeightKg(),
                p.getWeightLbs(),
                p.getExerciseLevel(),
                p.getGoal(),
                p.getDailyStepGoal(),
                p.getGoalWeightKg(),
                p.getGoalWeightLbs(),
                p.getUnitPreference(),
                p.getWorkoutsPerWeek(),
                p.getDailyWorkoutGoalKcal(),
                p.getKcal(),
                p.getCarbsG(),
                p.getProteinG(),
                p.getFatG(),
                p.getFiberG(),
                p.getSugarG(),
                p.getSodiumMg(),
                p.getWaterMl(),
                (p.getWaterMode() == null ? "AUTO" : p.getWaterMode().name()),
                p.getBmi(),
                p.getBmiClass(),
                (p.getPlanMode() == null ? "AUTO" : p.getPlanMode().name()),
                p.getCalcVersion(),
                p.getReferralSource(),
                p.getLocale(),
                p.getTimezone(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    @Transactional
    public void setManualNutritionGoals(
            Long userId,
            int kcal, int proteinG, int carbsG, int fatG,
            int fiberG, int sugarG, int sodiumMg
    ) {
        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));

        p.setKcal(Math.max(0, kcal));
        p.setProteinG(Math.max(0, proteinG));
        p.setCarbsG(Math.max(0, carbsG));
        p.setFatG(Math.max(0, fatG));

        p.setFiberG(Math.max(0, fiberG));
        p.setSugarG(Math.max(0, sugarG));
        p.setSodiumMg(Math.max(0, sodiumMg));

        p.setPlanMode(PlanMode.MANUAL);
        applyNotNullDefaults(p);

        // ✅ BMI/水量照你既有邏輯更新（可留可不留）
        recalcBmiFromLatestTimeseriesOrProfile(p, userId);
        recalcWaterIfAuto(p);

        repo.save(p);
    }

    // =========================
    // Auto Goals: Preview / Commit
    // =========================

    private record MergedAutoInputs(
            String gender,
            Integer age,
            Double heightCm,
            Double weightKg,
            Integer workoutsPerWeek,
            String goalKey,
            Double goalWeightKg,
            String unitPreference
    ) {}

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // 放在 UserProfileService 內部
    private MergedAutoInputs mergeAutoInputsForCommit(UserProfile p, AutoGoalsRequest r) {
        // gender/age 仍以 profile 為準（因為 req 沒帶）
        String gender = p.getGender();
        Integer age = p.getAge();

        // height：只看 req，不 fallback
        Double heightCm = null;
        if (r != null && r.heightFeet() != null && r.heightInches() != null) {
            short ft = r.heightFeet().shortValue();
            short in = r.heightInches().shortValue();
            heightCm = Units.feetInchesToCm(ft, in);
        } else if (r != null && r.heightCm() != null) {
            heightCm = r.heightCm();
        }
        if (heightCm != null) heightCm = Units.clamp(heightCm, MIN_HEIGHT_CM, MAX_HEIGHT_CM);

        // weight：只看 req，不 fallback
        Double weightKg = null;
        if (r != null && r.weightKg() != null) {
            weightKg = r.weightKg();
        } else if (r != null && r.weightLbs() != null) {
            weightKg = Units.lbsToKg1(r.weightLbs());
        }
        if (weightKg != null) weightKg = Units.clamp(weightKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);

        // workouts：建議也用 req（沒有就 null -> safeWorkouts 會變 0）
        Integer workouts = (r != null) ? r.workoutsPerWeek() : null;
        workouts = safeWorkouts(workouts);

        // goalKey：只用 req
        String goalKey = (!isBlank(r == null ? null : r.goalKey()))
                ? r.goalKey().trim().toUpperCase()
                : null;

        Double goalWeightKg = p.getGoalWeightKg();
        String unitPref = isBlank(p.getUnitPreference()) ? "KG" : p.getUnitPreference().trim().toUpperCase();

        return new MergedAutoInputs(
                gender, age, heightCm, weightKg, workouts, goalKey, goalWeightKg, unitPref
        );
    }

    // =========================
    // ✅ commitAutoGoals 三表同步用的小工具（只影響 commitAutoGoals，不動其他邏輯）
    // =========================

    private static BigDecimal bd1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    /** 同日 upsert 寫入 weight_history + weight_timeseries（不碰 photoUrl） */
    private void upsertWeightSnapshotSameDay(Long userId, LocalDate logDate, ZoneId zone, double kgVal, double lbsVal) {
        BigDecimal kg = bd1(kgVal);
        BigDecimal lbs = bd1(lbsVal);

        // history upsert
        WeightHistory h = weightHistory.findByUserIdAndLogDate(userId, logDate)
                .orElseGet(WeightHistory::new);
        h.setUserId(userId);
        h.setLogDate(logDate);
        h.setTimezone(zone.getId());
        h.setWeightKg(kg);
        h.setWeightLbs(lbs);
        // 不設定 photoUrl：避免覆寫同日原本照片
        weightHistory.save(h);

        // timeseries upsert
        WeightTimeseries s = weightSeries.findByUserIdAndLogDate(userId, logDate)
                .orElseGet(WeightTimeseries::new);
        s.setUserId(userId);
        s.setLogDate(logDate);
        s.setTimezone(zone.getId());
        s.setWeightKg(kg);
        s.setWeightLbs(lbs);
        weightSeries.save(s);
    }


    @Transactional
    public UserProfileDto commitAutoGoals(Long userId, AutoGoalsRequest req, String tzHeader) {
        log.info("commitAutoGoals uid={} tzHeader={} req={}",
                userId, tzHeader,
                String.format("hCm=%s ft=%s in=%s wKg=%s wLbs=%s workouts=%s goalKey=%s",
                        req.heightCm(), req.heightFeet(), req.heightInches(),
                        req.weightKg(), req.weightLbs(),
                        req.workoutsPerWeek(), req.goalKey()
                )
        );

        var p = repo.findByUserId(userId).orElseGet(() -> ensureDefault(userId));
        applyNotNullDefaults(p);

        MergedAutoInputs m = mergeAutoInputsForCommit(p, req);

        List<String> missing = new ArrayList<>();
        if (isBlank(m.gender())) missing.add("gender");
        if (m.age() == null) missing.add("age");
        if (m.heightCm() == null) missing.add("height");
        if (m.weightKg() == null) missing.add("weight");
        if (isBlank(m.goalKey())) missing.add("goalKey");

        if (!missing.isEmpty()) {
            throw new MissingFieldsException(missing);
        }

        // ---- 寫回 inputs（只寫 request 有帶的部分） ----
        // height
        if (req.heightFeet() != null && req.heightInches() != null) {
            short ft = req.heightFeet().shortValue();
            short in = req.heightInches().shortValue();
            p.setHeightFeet(ft);
            p.setHeightInches(in);

            Double cm = Units.feetInchesToCm(ft, in);
            cm = Units.clamp(cm, MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            p.setHeightCm(cm);
        } else if (req.heightCm() != null) {
            Double cm = Units.clamp(req.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            p.setHeightCm(cm);
            p.setHeightFeet(null);
            p.setHeightInches(null);
        }

        // weight（寫回 user_profiles）
        if (req.weightKg() != null || req.weightLbs() != null) {
            Double kgToSave;
            Double lbsToSave;

            if (req.weightKg() != null && req.weightLbs() != null) {
                kgToSave  = Units.clamp(req.weightKg(),  MIN_WEIGHT_KG,  MAX_WEIGHT_KG);
                lbsToSave = Units.clamp(req.weightLbs(), MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            } else if (req.weightKg() != null) {
                kgToSave  = Units.clamp(req.weightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                lbsToSave = Units.kgToLbs1(kgToSave);
                lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            } else {
                lbsToSave = Units.clamp(req.weightLbs(), MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                kgToSave  = Units.lbsToKg1(lbsToSave);
                kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
            }

            p.setWeightKg(kgToSave);
            p.setWeightLbs(lbsToSave);
        }

        if (req.workoutsPerWeek() != null) {
            int w = clampInt(req.workoutsPerWeek(), 7);
            p.setWorkoutsPerWeek(w);

            // ✅ 同步 exercise_level（避免 DB 留舊值 / null）
            String level = workoutsPerWeekToExerciseLevelKey(w);
            if (level != null) {
                p.setExerciseLevel(level);
            }
        }

        p.setGoal(m.goalKey()); // 已驗證必填

        // ✅ 若 commit 有帶 tzHeader，順便寫回 profile.timezone（讓後續行為一致）
        if (tzHeader != null && !tzHeader.isBlank()) {
            p.setTimezone(tzHeader.trim());
        }

        // ---- 重新計算 AUTO plan ----
        p.setPlanMode(PlanMode.AUTO);
        applyNotNullDefaults(p);

        var in = new PlanCalculator.Inputs(
                parseGender(p.getGender()),
                p.getAge(),
                p.getHeightCm().floatValue(),
                p.getWeightKg().floatValue(),
                safeWorkouts(p.getWorkoutsPerWeek())
        );
        var split = PlanCalculator.splitForGoalKey(p.getGoal());
        var plan = PlanCalculator.macroPlanBySplit(in, split, null);

        p.setKcal(plan.kcal());
        p.setCarbsG(plan.carbsGrams());
        p.setProteinG(plan.proteinGrams());
        p.setFatG(plan.fatGrams());
        p.setCalcVersion("Health_Calc_Auto_Generate");

        p.setFiberG(NutritionTargets.DEFAULT_FIBER_G);
        p.setSodiumMg(NutritionTargets.DEFAULT_SODIUM_MG);
        refreshNutritionTargetsFromKcal(p); // sugar 同步（AUTO）

        // ✅ water：用本次輸入計算（不讀 timeseries）
        p.setWaterMode(WaterMode.AUTO);
        p.setWaterMl(calcAutoWaterMl(p.getWeightKg(), p.getGender()));

        // ✅ BMI：用本次輸入計算（不讀 timeseries）
        recalcBmiFromProfile(p);

        // 先存 user_profiles
        var saved = repo.save(p);

        // =========================================================
        // ✅ 核心修改：不管新舊用戶，永遠把「本次 commit 的體重」寫入同日兩張表
        // - 一天最多一筆：由 uq(user_id, log_date) 保證
        // - 同日覆蓋：kg/lbs 更新，但不動 photoUrl（你的 upsert helper 已做到）
        // =========================================================
        ZoneId zone = parseZonePreferHeaderOrProfileOrUtc(tzHeader, saved.getTimezone());
        LocalDate logDate = LocalDate.now(zone);

        // saved.getWeightKg() 理論上必有（missing 已檢查），但保險一層
        if (saved.getWeightKg() != null) {
            double kgVal = Units.clamp(saved.getWeightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG);

            double lbsVal;
            if (saved.getWeightLbs() != null) {
                lbsVal = Units.clamp(saved.getWeightLbs(), MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            } else {
                lbsVal = Units.kgToLbs1(kgVal);
                lbsVal = Units.clamp(lbsVal, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            }

            // ✅ 推薦：用 retry 版本（避免並發下 unique constraint 偶發）
            upsertWeightSnapshotSameDayWithRetry(userId, logDate, zone, kgVal, lbsVal);
            // 若你不要 retry，就改成：
            // upsertWeightSnapshotSameDay(userId, logDate, zone, kgVal, lbsVal);
        }

        return toDto(saved);
    }

    /**
     * ✅ 同日 upsert（並發保險版）
     * - 遇到 race 造成 unique constraint（DataIntegrityViolationException）就重試一次
     */
    private void upsertWeightSnapshotSameDayWithRetry(
            Long userId, LocalDate logDate, ZoneId zone, double kgVal, double lbsVal
    ) {
        try {
            upsertWeightSnapshotSameDay(userId, logDate, zone, kgVal, lbsVal);
        } catch (DataIntegrityViolationException e) {
            // 可能是同日並發建立造成的 unique constraint race：重抓再存
            upsertWeightSnapshotSameDay(userId, logDate, zone, kgVal, lbsVal);
        }
    }

    // ✅ 優先用 header；沒有就用 profile.timezone；最後 UTC
    private static ZoneId parseZonePreferHeaderOrProfileOrUtc(String tzHeader, String profileTz) {
        String raw = (tzHeader != null && !tzHeader.isBlank())
                ? tzHeader.trim()
                : (profileTz != null && !profileTz.isBlank() ? profileTz.trim() : "UTC");
        try {
            return ZoneId.of(raw);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

}
