package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodLogOverrideRepository extends JpaRepository<FoodLogOverrideEntity, String> {
}
