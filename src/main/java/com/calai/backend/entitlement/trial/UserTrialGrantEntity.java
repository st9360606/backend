package com.calai.backend.entitlement.trial;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_trial_grants")
public class UserTrialGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_hash", length = 64, nullable = false, unique = true)
    private String emailHash;

    @Column(name = "device_hash", length = 64, nullable = false, unique = true)
    private String deviceHash;

    @Column(name = "first_user_id", nullable = false)
    private Long firstUserId;

    @Column(name = "granted_at_utc", nullable = false)
    private Instant grantedAtUtc;
}
