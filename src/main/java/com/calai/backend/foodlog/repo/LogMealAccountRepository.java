package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.LogMealAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogMealAccountRepository extends JpaRepository<LogMealAccountEntity, Long> {
    Optional<LogMealAccountEntity> findByUserId(Long userId);
}
