package com.calai.backend.userprofile.entity;

import com.calai.backend.users.entity.User;
import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "user_profiles")
public class UserProfile {
    @Id @Column(name = "user_id") private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id") private User user;

    @Column(name = "gender") private String gender;
    @Column(name = "age") private Integer age;
    @Column(name = "height_cm") private Double heightCm;
    @Column(name = "height_feet")private Short heightFeet;
    @Column(name = "height_inches") private Short heightInches;
    @Column(name = "weight_kg") private Double weightKg;
    @Column(name = "weight_lbs")private Double weightLbs;        // ★ Integer -> Double
    @Column(name = "exercise_level")private String exerciseLevel;
    @Column(name = "goal")private String goal;
    @Column(name = "daily_step_goal") private Integer dailyStepGoal;
    @Column(name = "goal_weight_kg") private Double goalWeightKg;
    @Column(name = "goal_weight_lbs") private Double goalWeightLbs; // ★ Integer -> Double

    @Column(name = "unit_preference", nullable = false)
    private String unitPreference;

    @Column(name = "workouts_per_week")
    private Integer workoutsPerWeek;

    @Column(name = "kcal", nullable = false)
    private Integer kcal;

    @Column(name = "carbs_g", nullable = false)
    private Integer carbsG;

    @Column(name = "protein_g", nullable = false)
    private Integer proteinG;

    @Column(name = "fat_g", nullable = false)
    private Integer fatG;

    @Column(name = "water_ml", nullable = false)
    private Integer waterMl;

    @Column(name = "bmi", nullable = false)
    private Double bmi;

    @Column(name = "bmi_class", nullable = false)
    private String bmiClass;

    @Column(name = "calc_version", nullable = false)
    private String calcVersion;

    @Column(name = "referral_source")private String referralSource;
    @Column(name = "locale")private String locale;
    @Column(name = "timezone") private String timezone; // ★ 新增：使用者時區（例如 "Asia/Taipei"）
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate(){ this.updatedAt = Instant.now(); }
}
