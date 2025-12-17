package com.calai.backend.users.activity.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "user_daily_activity",
        uniqueConstraints = @UniqueConstraint(name="uq_user_day", columnNames={"user_id","local_date"}))
public class UserDailyActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="local_date", nullable=false)
    private LocalDate localDate;

    @Column(nullable=false, length=64)
    private String timezone;

    @Column(name="day_start_utc", nullable=false)
    private Instant dayStartUtc;

    @Column(name="day_end_utc", nullable=false)
    private Instant dayEndUtc;

    @Column(nullable=false)
    private Long steps = 0L;

    @Column(name="total_kcal", nullable=false)
    private Double totalKcal = 0.0;

    @Column(nullable=false, length=32)
    private String source = "HEALTH_CONNECT";

    @Column(name="updated_at", nullable=false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onCreate() { this.updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

}
