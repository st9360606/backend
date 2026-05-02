package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.UserNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    boolean existsByUserIdAndSourceTypeAndSourceRefId(Long userId, String sourceType, Long sourceRefId);

    List<UserNotificationEntity> findTop50ByUserIdOrderByCreatedAtUtcDesc(Long userId);

    List<UserNotificationEntity> findTop20ByUserIdOrderByCreatedAtUtcDesc(Long userId);

    List<UserNotificationEntity> findTop20ByUserIdAndSourceTypeAndSourceRefIdOrderByCreatedAtUtcDesc(
            Long userId,
            String sourceType,
            Long sourceRefId
    );
}
