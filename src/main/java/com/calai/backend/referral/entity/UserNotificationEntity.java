package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_notifications",
        indexes = @Index(name = "idx_user_notifications_user_created", columnList = "user_id,created_at_utc"),
        uniqueConstraints = @UniqueConstraint(name = "ux_user_notifications_source", columnNames = {"user_id", "source_type", "source_ref_id"}))
public class UserNotificationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "deep_link", length = 128)
    private String deepLink;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_ref_id", nullable = false)
    private Long sourceRefId;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @PrePersist
    void prePersist() {
        if (createdAtUtc == null) createdAtUtc = Instant.now();
    }
}
