package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "user_daily_nutrition_summary",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_daily_nutrition_summary_user_date", columnNames = {"user_id", "local_date"}))
public class UserDailyNutritionSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "total_kcal", nullable = false)
    private Double totalKcal = 0d;

    @Column(name = "total_protein_g", nullable = false)
    private Double totalProteinG = 0d;

    @Column(name = "total_carbs_g", nullable = false)
    private Double totalCarbsG = 0d;

    @Column(name = "total_fats_g", nullable = false)
    private Double totalFatsG = 0d;

    @Column(name = "total_fiber_g", nullable = false)
    private Double totalFiberG = 0d;

    @Column(name = "total_sugar_g", nullable = false)
    private Double totalSugarG = 0d;

    @Column(name = "total_sodium_mg", nullable = false)
    private Double totalSodiumMg = 0d;

    @Column(name = "meal_count", nullable = false)
    private Integer mealCount = 0;

    @Column(name = "last_recomputed_at_utc", nullable = false)
    private Instant lastRecomputedAtUtc;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (lastRecomputedAtUtc == null) lastRecomputedAtUtc = now;
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
        if (timezone == null || timezone.isBlank()) timezone = "UTC";
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
        if (timezone == null || timezone.isBlank()) timezone = "UTC";
    }
}
