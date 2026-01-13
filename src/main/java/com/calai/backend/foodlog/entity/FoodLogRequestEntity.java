package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "food_log_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_food_log_requests_user_req",
                columnNames = {"user_id", "request_id"}
        ),
        indexes = @Index(name = "idx_food_log_requests_log", columnList = "food_log_id")
)
public class FoodLogRequestEntity {

    public enum ReqStatus { RESERVED, ATTACHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;

    @Column(name = "food_log_id", length = 36)
    private String foodLogId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReqStatus status = ReqStatus.RESERVED;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
        if (status == null) status = ReqStatus.RESERVED;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
