package com.calai.backend.userprofile.service;

import com.calai.backend.auth.repo.UserRepo;
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
     * 僅覆寫「非 null」的欄位，避免把既有值覆成 null。
     */
    @Transactional
    public UserProfileDto upsert(Long userId, UpsertProfileRequest r) {
        var u = users.findById(userId).orElseThrow(); // 既有 users 表
        var p = repo.findByUserId(userId).orElseGet(() -> {
            var np = new UserProfile();
            // 使用 @MapsId 的情況，設定 user 會同步成為主鍵 user_id
            np.setUser(u);
            return np;
        });

        // 只在請求欄位非 null 時才覆寫
        if (r.gender() != null)           p.setGender(r.gender());
        if (r.age() != null)              p.setAge(r.age());
        if (r.heightCm() != null)         p.setHeightCm(r.heightCm());
        if (r.weightKg() != null)         p.setWeightKg(r.weightKg());
        if (r.exerciseLevel() != null)    p.setExerciseLevel(r.exerciseLevel());
        if (r.goal() != null)             p.setGoal(r.goal());
        if (r.targetWeightKg() != null)   p.setTargetWeightKg(r.targetWeightKg());
        if (r.referralSource() != null)   p.setReferralSource(r.referralSource());
        if (r.locale() != null)           p.setLocale(r.locale());

        var saved = repo.save(p);
        return toDto(saved);
    }

    private static UserProfileDto toDto(UserProfile p) {
        return new UserProfileDto(
                p.getGender(), p.getAge(), p.getHeightCm(), p.getWeightKg(),
                p.getExerciseLevel(), p.getGoal(), p.getTargetWeightKg(),
                p.getReferralSource(), p.getLocale()
        );
    }
}
