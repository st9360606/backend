package com.calai.backend.userprofile.service;

import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.userprofile.common.Units;
import com.calai.backend.userprofile.dto.UpsertProfileRequest;
import com.calai.backend.userprofile.dto.UserProfileDto;
import com.calai.backend.userprofile.entity.UserProfile;
import com.calai.backend.userprofile.repo.UserProfileRepository;
import com.calai.backend.users.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class UserProfileService {
    private final UserProfileRepository repo;
    private final UserRepo users;

    // 建議視實際需求調整邊界
    private static final double MIN_HEIGHT_CM = 80.0d;
    private static final double MAX_HEIGHT_CM = 300.0d;
    private static final double MIN_WEIGHT_KG = 20.0d;
    private static final double MAX_WEIGHT_KG = 800.0d;
    private static final double MIN_WEIGHT_LBS = 40.0d;
    private static final double MAX_WEIGHT_LBS = 900.0d;

    public UserProfileService(UserProfileRepository repo, UserRepo users) {
        this.repo = repo;
        this.users = users;
    }

    /** 首次登入或缺資料時：確保至少有一筆最小 Profile（避免前端先遇到 404/401） */
    @Transactional
    public UserProfile ensureDefault(User u) {
        return repo.findByUserId(u.getId()).orElseGet(() -> {
            var np = new UserProfile();
            np.setUser(u);                 // @MapsId: 以 user.id 當 user_id
            // 這裡只給安全預設；不要亂填身高體重避免造成 UI 誤導
            // 可選：np.setLocale("en");
            return repo.save(np);
        });
    }

    /** 以 userId 確保存在（便於只拿到 id 的情境） */
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

    /**
     * 建立或更新使用者的 Profile。
     * 身高/體重採兩制同步規則；其他欄位維持「非 null 才覆寫」。
     */
    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r) {
        var u = users.findById(userId).orElseThrow();
        var p = repo.findByUserId(userId).orElseGet(() -> {
            var np = new UserProfile();
            np.setUser(u);
            return np;
        });

        // ---------- 身高 ----------
        if (r.heightFeet() != null && r.heightInches() != null) {
            // 使用者以 ft/in 為主
            short ft = r.heightFeet();
            short in = r.heightInches();

            p.setHeightFeet(ft);
            p.setHeightInches(in);

            Double cm;
            if (r.heightCm() != null) {
                // client 有帶 cm：只做 clamp，不再 floor，避免 170.1 → 170.0
                cm = Units.clamp(r.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            } else {
                // server 自己算 cm：用 feet+inches → cm，這裡才做 0.1cm floor
                cm = Units.feetInchesToCm(ft, in);
                cm = Units.clamp(cm, MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            }
            p.setHeightCm(cm);
        } else if (r.heightCm() != null) {
            // 使用者以 cm 為主
            Double cm = Units.clamp(r.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
            p.setHeightCm(cm);
            p.setHeightFeet(null);
            p.setHeightInches(null);
        }

        // ---------- 現在體重（current）----------
        {
            Double reqKg  = r.weightKg();
            Double reqLbs = r.weightLbs();

            if (reqKg != null || reqLbs != null) {
                Double kgToSave;
                Double lbsToSave;

                if (reqKg != null && reqLbs != null) {
                    // 新版 app：兩個都帶，視為「各自都是原始單位」，只 clamp 不改小數
                    kgToSave  = Units.clamp(reqKg,  MIN_WEIGHT_KG,  MAX_WEIGHT_KG);
                    lbsToSave = Units.clamp(reqLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else if (reqKg != null) {
                    // 只有 kg：kg 當原始，lbs 由 server 換算（0.1 無條件捨去）
                    kgToSave  = Units.clamp(reqKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                    lbsToSave = Units.kgToLbs1(kgToSave);
                    lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else { // 只有 lbs
                    lbsToSave = Units.clamp(reqLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                    kgToSave  = Units.lbsToKg1(lbsToSave);
                    kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                }

                p.setWeightKg(kgToSave);
                p.setWeightLbs(lbsToSave);
            }
        }

        // ---------- 目標體重（target）----------
        {
            Double reqTargetKg  = r.targetWeightKg();
            Double reqTargetLbs = r.targetWeightLbs();

            if (reqTargetKg != null || reqTargetLbs != null) {
                Double kgToSave;
                Double lbsToSave;

                if (reqTargetKg != null && reqTargetLbs != null) {
                    kgToSave  = Units.clamp(reqTargetKg,  MIN_WEIGHT_KG,  MAX_WEIGHT_KG);
                    lbsToSave = Units.clamp(reqTargetLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else if (reqTargetKg != null) {
                    kgToSave  = Units.clamp(reqTargetKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                    lbsToSave = Units.kgToLbs1(kgToSave);
                    lbsToSave = Units.clamp(lbsToSave, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                } else { // 只有 lbs
                    lbsToSave = Units.clamp(reqTargetLbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
                    kgToSave  = Units.lbsToKg1(lbsToSave);
                    kgToSave  = Units.clamp(kgToSave, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
                }

                p.setTargetWeightKg(kgToSave);
                p.setTargetWeightLbs(lbsToSave);
            }
        }

        // ---------- 其他欄位：非 null 才覆寫 ----------
        if (r.gender() != null)         p.setGender(r.gender());
        if (r.age() != null)            p.setAge(r.age());
        if (r.exerciseLevel() != null)  p.setExerciseLevel(r.exerciseLevel());
        if (r.goal() != null)           p.setGoal(r.goal());
        if (r.referralSource() != null) p.setReferralSource(r.referralSource());
        if (r.locale() != null)         p.setLocale(r.locale());

        var saved = repo.save(p);
        return toDto(saved);
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
                p.getTargetWeightKg(),
                p.getTargetWeightLbs(),
                p.getReferralSource(),
                p.getLocale()
        );
    }

    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r, String tz) {
        var dto = upsert(userId, r);
        repo.findByUserId(userId).ifPresent(p -> {
            if (tz != null && !tz.isBlank()) {
                p.setTimezone(tz);
                repo.save(p);
            }
        });
        return dto;
    }

    /**
     * 從體重紀錄更新目前體重（kg & lbs）。
     * - 若尚未有 profile，自動建立一筆（沿用 ensureDefault 的行為）。
     * - 每次寫入 weight 時都會覆寫成「最後一次紀錄」。
     */
    @Transactional
    public void updateCurrentWeight(Long userId, BigDecimal weightKg, BigDecimal weightLbs) {
        if (weightKg == null && weightLbs == null) return;

        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 確保最少有一筆 profile
        UserProfile profile = repo.findByUserId(userId).orElseGet(() -> {
            UserProfile np = new UserProfile();
            np.setUser(user);
            return repo.save(np);
        });

        if (weightKg != null) {
            profile.setWeightKg(weightKg.doubleValue());
        }
        if (weightLbs != null) {
            profile.setWeightLbs(weightLbs.doubleValue());
        }

        repo.save(profile);
    }
}
