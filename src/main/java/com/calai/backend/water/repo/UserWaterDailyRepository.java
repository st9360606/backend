package com.calai.backend.water.repo;

import com.calai.backend.water.entity.UserWaterDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UserWaterDailyRepository extends JpaRepository<UserWaterDaily, Long> {
    Optional<UserWaterDaily> findByUserIdAndLocalDate(Long userId, LocalDate localDate);
    void deleteByLocalDateBefore(LocalDate cutoffDate);
}
