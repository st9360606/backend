package com.caloshape.backend.fasting.repo;

import com.caloshape.backend.fasting.entity.FastingPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FastingPlanRepository extends JpaRepository<FastingPlanEntity, Long> {
    Optional<FastingPlanEntity> findByUserId(Long userId);
}
