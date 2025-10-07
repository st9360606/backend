package com.calai.backend.userprofile.entity;


import com.calai.backend.auth.entity.User;
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
    @Column(name = "weight_kg") private Double weightKg;
    @Column(name = "exercise_level") private String exerciseLevel;
    private String goal;
    @Column(name = "target_weight_kg") private Double targetWeightKg;
    @Column(name = "referral_source") private String referralSource;
    private String locale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate(){ this.updatedAt = Instant.now(); }


}
