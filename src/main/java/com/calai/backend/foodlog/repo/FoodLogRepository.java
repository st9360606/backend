package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FoodLogRepository extends JpaRepository<FoodLogEntity, String> {
    Optional<FoodLogEntity> findByIdAndUserId(String id, Long userId);
}
