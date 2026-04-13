package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(
        name = "user_daily_workout_summary",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_daily_workout_summary_user_date",
                columnNames = {"user_id", "local_date"}
        )
)
public class UserDailyWorkoutSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "workout_kcal", nullable = false)
    private Double workoutKcal = 0d;

    @Column(name = "activity_kcal", nullable = false)
    private Double activityKcal = 0d;

    @Column(name = "total_burned_kcal", nullable = false)
    private Double totalBurnedKcal = 0d;

    @Column(name = "workout_session_count", nullable = false)
    private Integer workoutSessionCount = 0;

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
