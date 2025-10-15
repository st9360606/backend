package com.calai.backend.userprofile.service;

import com.calai.backend.auth.repo.UserRepo;
import com.calai.backend.userprofile.common.Units;
import com.calai.backend.userprofile.dto.UpsertProfileRequest;
import com.calai.backend.userprofile.dto.UserProfileDto;
import com.calai.backend.userprofile.entity.UserProfile;
import com.calai.backend.userprofile.repo.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
    private final UserProfileRepository repo;
    private final UserRepo users;

    public UserProfileService(UserProfileRepository repo, UserRepo users) {
        this.repo = repo;
        this.users = users;
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
     * 除身高/體重採專屬規則（兩制同步/清空），其他欄位維持「非 null 才覆寫」。
     */
    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r) {
        var u = users.findById(userId).orElseThrow();
        var p = repo.findByUserId(userId).orElseGet(() -> {
            var np = new UserProfile();
            np.setUser(u); // @MapsId：會把 user.id 寫入 user_id
            return np;
        });

        // -------------- 在身高 --------------
        if (r.heightFeet() != null && r.heightInches() != null) {
            // 英制 + 公制（若沒帶 cm 就由 ft/in 算）
            p.setHeightFeet(r.heightFeet());
            p.setHeightInches(r.heightInches());
            Double cm = (r.heightCm() != null)
                    ? r.heightCm()
                    : com.calai.backend.userprofile.common.Units.feetInchesToCm(r.heightFeet(), r.heightInches());
            p.setHeightCm(cm);
        } else if (r.heightCm() != null) {
            // 只有 cm，並清空英制
            p.setHeightCm(r.heightCm());
            p.setHeightFeet(null);
            p.setHeightInches(null);
        }

        // -------------- 在體重 --------------
        if (r.weightLbs() != null) {
            // lbs + kg（若沒帶 kg 就由 lbs 算）
            p.setWeightLbs(r.weightLbs());
            Double kg = (r.weightKg() != null)
                    ? r.weightKg()
                    : com.calai.backend.userprofile.common.Units.lbsToKg(r.weightLbs());
            p.setWeightKg(kg);
        } else if (r.weightKg() != null) {
            // 只有 kg，並清空 lbs
            p.setWeightKg(r.weightKg());
            p.setWeightLbs(null);
        }

        // -------------- 目標體重 --------------
        if (r.targetWeightLbs() != null) {
            p.setTargetWeightLbs(r.targetWeightLbs());
            Double kg = (r.targetWeightKg() != null)
                    ? r.targetWeightKg()
                    : com.calai.backend.userprofile.common.Units.lbsToKg(r.targetWeightLbs());
            p.setTargetWeightKg(kg);
        } else if (r.targetWeightKg() != null) {
            p.setTargetWeightKg(r.targetWeightKg());
            p.setTargetWeightLbs(null);
        }

        // ---------- 其他欄位：非 null 才覆寫 ----------
        if (r.gender() != null) p.setGender(r.gender());
        if (r.age() != null) p.setAge(r.age());
        if (r.exerciseLevel() != null) p.setExerciseLevel(r.exerciseLevel());
        if (r.goal() != null) p.setGoal(r.goal());
        if (r.referralSource() != null) p.setReferralSource(r.referralSource());
        if (r.locale() != null) p.setLocale(r.locale());

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
}
