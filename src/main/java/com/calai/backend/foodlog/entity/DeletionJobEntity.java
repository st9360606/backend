package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "deletion_jobs",
        indexes = {
                @Index(name = "idx_deletion_jobs_food_log_id", columnList = "food_log_id"),
                @Index(name = "idx_deletion_jobs_status", columnList = "job_status,next_retry_at_utc")
        }
)
public class DeletionJobEntity {

    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "food_log_id", length = 36, nullable = false)
    private String foodLogId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 64)
    private String sha256;

    @Column(length = 8)
    private String ext;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", length = 16, nullable = false)
    private JobStatus jobStatus;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_retry_at_utc")
    private Instant nextRetryAtUtc;

    @Column(name = "image_object_key", columnDefinition = "TEXT")
    private String imageObjectKey;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

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
        if (jobStatus == null) jobStatus = JobStatus.QUEUED;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }

    public void markRunning(Instant now) {
        this.jobStatus = JobStatus.RUNNING;
        this.attempts += 1;
        this.updatedAtUtc = now;
    }

    public void markSucceeded(Instant now) {
        this.jobStatus = JobStatus.SUCCEEDED;
        this.nextRetryAtUtc = null;
        this.lastError = null;
        this.updatedAtUtc = now;
    }

    public void markFailed(Instant now, String err, int retryAfterSec) {
        this.jobStatus = JobStatus.FAILED;
        this.lastError = err;
        this.nextRetryAtUtc = now.plusSeconds(retryAfterSec);
        this.updatedAtUtc = now;
    }

    public void markCancelled(Instant now, String err) {
        this.jobStatus = JobStatus.CANCELLED;
        this.lastError = err;
        this.nextRetryAtUtc = null;
        this.updatedAtUtc = now;
    }
}
