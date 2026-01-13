package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "image_blobs",
        uniqueConstraints = @UniqueConstraint(name = "uk_image_blobs_user_sha", columnNames = {"user_id", "sha256"}),
        indexes = @Index(name = "idx_image_blobs_user", columnList = "user_id")
)
public class ImageBlobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(length = 64, nullable=false)
    private String sha256;

    @Column(name="object_key", columnDefinition = "TEXT", nullable=false)
    private String objectKey;

    @Column(name="content_type", length=64, nullable=false)
    private String contentType;

    @Column(name="size_bytes", nullable=false)
    private Long sizeBytes;

    @Column(length=8, nullable=false)
    private String ext;

    @Column(name="ref_count", nullable=false)
    private Integer refCount = 1;

    @Column(name="created_at_utc", nullable=false)
    private Instant createdAtUtc;

    @Column(name="updated_at_utc", nullable=false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
        if (refCount == null) refCount = 1;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
