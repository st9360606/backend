package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoodLogOverrideRepository extends JpaRepository<FoodLogOverrideEntity, String> {

    @Modifying
    @Query("delete from FoodLogOverrideEntity o where o.foodLogId = :foodLogId")
    int deleteByFoodLogId(@Param("foodLogId") String foodLogId);
}