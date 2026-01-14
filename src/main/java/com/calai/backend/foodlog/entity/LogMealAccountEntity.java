package com.calai.backend.foodlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "logmeal_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_logmeal_accounts_user", columnNames = "user_id"),
        indexes = @Index(name = "idx_logmeal_accounts_status", columnList = "status")
)
public class LogMealAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="api_user_token_enc", columnDefinition = "TEXT", nullable=false)
    private String apiUserTokenEnc;

    @Column(length = 16, nullable=false)
    private String status = "ACTIVE";

    @Column(name="created_at_utc", nullable=false)
    private Instant createdAtUtc;

    @Column(name="updated_at_utc", nullable=false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
        if (status == null) status = "ACTIVE";
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }
}
