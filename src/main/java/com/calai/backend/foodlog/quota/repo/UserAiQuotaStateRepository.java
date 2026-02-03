package com.calai.backend.foodlog.quota.repo;

import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface UserAiQuotaStateRepository extends JpaRepository<UserAiQuotaStateEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserAiQuotaStateEntity s where s.userId = :userId")
    Optional<UserAiQuotaStateEntity> findForUpdate(@Param("userId") Long userId);
}