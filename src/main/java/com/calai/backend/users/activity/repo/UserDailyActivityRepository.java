package com.calai.backend.users.activity.repo;

import com.calai.backend.users.activity.entity.UserDailyActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserDailyActivityRepository extends JpaRepository<UserDailyActivity, Long> {

    Optional<UserDailyActivity> findByUserIdAndLocalDate(Long userId, LocalDate localDate);

    List<UserDailyActivity> findByUserIdAndLocalDateBetweenOrderByLocalDateAsc(
            Long userId, LocalDate from, LocalDate to
    );

    @Modifying
    @Query("delete from UserDailyActivity a where a.dayEndUtc < :cutoff")
    int deleteAllExpired(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from UserDailyActivity a where a.userId = :userId and a.dayEndUtc < :cutoff")
    int deleteExpiredForUser(@Param("userId") Long userId,
                             @Param("cutoff") Instant cutoff);
}
