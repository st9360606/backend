package com.caloshape.backend.referral.repo;

import com.caloshape.backend.referral.entity.UserNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    boolean existsByUserIdAndSourceTypeAndSourceRefId(Long userId, String sourceType, Long sourceRefId);

    Optional<UserNotificationEntity> findByUserIdAndSourceTypeAndSourceRefId(
            Long userId,
            String sourceType,
            Long sourceRefId
    );

    Optional<UserNotificationEntity> findByIdAndUserId(Long id, Long userId);

    List<UserNotificationEntity> findTop50ByUserIdOrderByCreatedAtUtcDesc(Long userId);

    List<UserNotificationEntity> findTop20ByUserIdOrderByCreatedAtUtcDesc(Long userId);

    List<UserNotificationEntity> findTop20ByUserIdAndSourceTypeAndSourceRefIdOrderByCreatedAtUtcDesc(
            Long userId,
            String sourceType,
            Long sourceRefId
    );
}
