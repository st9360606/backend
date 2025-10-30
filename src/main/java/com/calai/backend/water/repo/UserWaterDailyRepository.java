package com.calai.backend.water.repo;

import com.calai.backend.water.entity.UserWaterDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UserWaterDailyRepository extends JpaRepository<UserWaterDaily, Long> {

    Optional<UserWaterDaily> findByUserIdAndLocalDate(Long userId, LocalDate localDate);

    // 全庫刪除：刪掉 < cutoff（保留 T-7）
    void deleteByLocalDateBefore(LocalDate cutoffDate);

    // 單用戶刪除：刪掉 < cutoff（保留 T-7）
    void deleteByUserIdAndLocalDateBefore(Long userId, LocalDate cutoffDate);

    // （保留以防別處仍引用）單用戶刪除：刪掉 <= cutoff（會刪 T-7）
    void deleteByUserIdAndLocalDateLessThanEqual(Long userId, LocalDate cutoffDate);
}
