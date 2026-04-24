package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.UserNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    List<UserNotificationEntity> findTop50ByUserIdOrderByCreatedAtUtcDesc(Long userId);
}
