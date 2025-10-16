package com.calai.backend.userprofile.entity;

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

    private String gender;
    private Integer age;

    @Column(name = "height_cm") private Double heightCm;

    // ★ 新增：英制身高
    @Column(name = "height_feet")   private Short heightFeet;   // 0..8
    @Column(name = "height_inches") private Short heightInches; // 0..11

    @Column(name = "weight_kg") private Double weightKg;

    // ★ 新增：英制體重（現在）
    @Column(name = "weight_lbs") private Integer weightLbs;     // 40..900

    @Column(name = "exercise_level") private String exerciseLevel;
    private String goal;

    @Column(name = "target_weight_kg") private Double targetWeightKg;

    // ★ 新增：英制體重（目標）
    @Column(name = "target_weight_lbs") private Integer targetWeightLbs; // 40..900

    @Column(name = "referral_source") private String referralSource;
    private String locale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate(){ this.updatedAt = Instant.now(); }
}
