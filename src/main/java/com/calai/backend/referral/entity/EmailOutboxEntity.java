package com.calai.backend.referral.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "email_outbox",
        indexes = @Index(name = "idx_email_outbox_status_created", columnList = "status,created_at_utc"),
        uniqueConstraints = @UniqueConstraint(name = "ux_email_outbox_dedupe", columnNames = "dedupe_key"))
public class EmailOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "to_email", nullable = false, length = 320)
    private String toEmail;

    @Column(name = "template_type", nullable = false, length = 32)
    private String templateType;

    @Column(name = "template_payload_json", nullable = false, columnDefinition = "json")
    private String templatePayloadJson;

    @Column(name = "dedupe_key", nullable = false, length = 100)
    private String dedupeKey;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "sent_at_utc")
    private Instant sentAtUtc;

    @PrePersist
    void prePersist() {
        if (createdAtUtc == null) createdAtUtc = Instant.now();
    }
}
