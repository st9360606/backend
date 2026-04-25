package com.calai.backend.entitlement.trial;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "user_trial_grants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_trial_email", columnNames = "email_hash"),
                @UniqueConstraint(name = "uq_trial_device", columnNames = "device_hash")
        },
        indexes = {
                @Index(name = "idx_trial_first_user", columnList = "first_user_id")
        }
)
public class UserTrialGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_hash", length = 64, nullable = false)
    private String emailHash;

    @Column(name = "device_hash", length = 64, nullable = false)
    private String deviceHash;

    @Column(name = "first_user_id", nullable = false)
    private Long firstUserId;

    @Column(name = "granted_at_utc", nullable = false)
    private Instant grantedAtUtc;
}
