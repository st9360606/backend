package com.calai.backend.userprofile.entity;

import com.calai.backend.userprofile.common.PlanMode;
import com.calai.backend.userprofile.common.WaterMode;
import com.calai.backend.users.entity.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "gender") private String gender;
    @Column(name = "age") private Integer age;

    @Column(name = "height_cm") private Double heightCm;
    @Column(name = "height_feet") private Short heightFeet;
    @Column(name = "height_inches") private Short heightInches;

    @Column(name = "weight_kg") private Double weightKg;
    @Column(name = "weight_lbs") private Double weightLbs;

    @Column(name = "exercise_level") private String exerciseLevel;
    @Column(name = "goal") private String goal;

    @Column(name = "daily_step_goal", nullable = false)
    private Integer dailyStepGoal = 10000;

    @Column(name = "goal_weight_kg") private Double goalWeightKg;
    @Column(name = "goal_weight_lbs") private Double goalWeightLbs;

    @Column(name = "unit_preference", nullable = false)
    private String unitPreference = "KG";

    @Column(name = "workouts_per_week")
    private Integer workoutsPerWeek;

    @Column(name = "kcal", nullable = false)
    private Integer kcal = 0;

    @Column(name = "carbs_g", nullable = false)
    private Integer carbsG = 0;

    @Column(name = "protein_g", nullable = false)
    private Integer proteinG = 0;

    @Column(name = "fat_g", nullable = false)
    private Integer fatG = 0;

    @Column(name = "fiber_g", nullable = false)
    private Integer fiberG = 35;

    @Column(name = "sugar_g", nullable = false)
    private Integer sugarG = 0;

    @Column(name = "sodium_mg", nullable = false)
    private Integer sodiumMg = 2300;

    @Column(name = "water_ml", nullable = false)
    private Integer waterMl = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "water_mode", nullable = false)
    private WaterMode waterMode = WaterMode.AUTO;

    @Column(name = "bmi", nullable = false)
    private Double bmi = 0.0;

    @Column(name = "bmi_class", nullable = false)
    private String bmiClass = "UNKNOWN";

    // ✅ 重要：要對到 DB 的 plan_mode 欄位
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_mode", nullable = false)
    private PlanMode planMode = PlanMode.AUTO;

    @Column(name = "calc_version", nullable = false)
    private String calcVersion = "healthcalc_v1";

    @Column(name = "referral_source") private String referralSource;
    @Column(name = "locale") private String locale;
    @Column(name = "timezone") private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
