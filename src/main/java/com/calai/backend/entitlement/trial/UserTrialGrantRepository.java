package com.calai.backend.entitlement.trial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTrialGrantRepository extends JpaRepository<UserTrialGrantEntity, Long> {
    Optional<UserTrialGrantEntity> findByEmailHash(String emailHash);
    Optional<UserTrialGrantEntity> findByDeviceHash(String deviceHash);
}
