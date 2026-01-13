package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "usage_counters",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_counters_user_date", columnNames = {"user_id","local_date"}),
        indexes = @Index(name = "idx_usage_counters_user_date", columnList = "user_id,local_date")
)
public class UsageCounterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (updatedAtUtc == null) updatedAtUtc = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
