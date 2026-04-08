package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.UserDailyNutritionSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserDailyNutritionSummaryRepository extends JpaRepository<UserDailyNutritionSummaryEntity, Long> {

    Optional<UserDailyNutritionSummaryEntity> findByUserIdAndLocalDate(Long userId, LocalDate localDate);

    List<UserDailyNutritionSummaryEntity> findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
            Long userId,
            LocalDate from,
            LocalDate to
    );

    int deleteByLocalDateBefore(LocalDate cutoff);
}
