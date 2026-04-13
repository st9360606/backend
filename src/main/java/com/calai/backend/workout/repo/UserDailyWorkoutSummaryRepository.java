package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.UserDailyWorkoutSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserDailyWorkoutSummaryRepository extends JpaRepository<UserDailyWorkoutSummaryEntity, Long> {

    Optional<UserDailyWorkoutSummaryEntity> findByUserIdAndLocalDate(Long userId, LocalDate localDate);

    List<UserDailyWorkoutSummaryEntity> findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
            Long userId,
            LocalDate from,
            LocalDate to
    );

    int deleteByLocalDateBefore(LocalDate cutoff);
}
