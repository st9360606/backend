package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "food_log_tasks",
        uniqueConstraints = @UniqueConstraint(name = "ux_food_log_tasks_food_log_id", columnNames = "food_log_id"),
        indexes = @Index(name = "idx_food_log_tasks_status", columnList = "task_status,next_retry_at_utc")
)
public class FoodLogTaskEntity {

    public enum TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "food_log_id", length = 36, nullable = false)
    private String foodLogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", length = 16, nullable = false)
    private TaskStatus taskStatus;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_retry_at_utc")
    private Instant nextRetryAtUtc;

    @Column(name = "poll_after_sec", nullable = false)
    private int pollAfterSec = 2;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
        if (taskStatus == null) taskStatus = TaskStatus.QUEUED;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }

    public void markRunning(Instant now) {
        this.taskStatus = TaskStatus.RUNNING;
        this.attempts += 1;
        this.updatedAtUtc = now;
    }

    public void markSucceeded(Instant now) {
        this.taskStatus = TaskStatus.SUCCEEDED;
        this.nextRetryAtUtc = null;
        this.updatedAtUtc = now;
    }

    public void markFailed(Instant now, String code, String message, int retryAfterSec) {
        this.taskStatus = TaskStatus.FAILED;
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.nextRetryAtUtc = now.plusSeconds(retryAfterSec);
        this.updatedAtUtc = now;
    }
}
