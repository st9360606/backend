package com.calai.backend.users.activity.repo;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

public interface UserDailyActivityRepository extends JpaRepository<UserDailyActivity, Long> {
    Optional<UserDailyActivity> findByUserIdAndLocalDate(Long userId, LocalDate localDate);
    List<UserDailyActivity> findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(Long userId, LocalDate from, LocalDate to);
}

