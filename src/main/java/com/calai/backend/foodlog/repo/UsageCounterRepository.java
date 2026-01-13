package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.UsageCounterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;

public interface UsageCounterRepository extends JpaRepository<UsageCounterEntity, Long> {

    /** row 不存在就插入 used_count=1；存在則 0 rows */
    @Modifying
    @Query(
            value = """
        INSERT IGNORE INTO usage_counters(user_id, local_date, used_count, updated_at_utc)
        VALUES (:userId, :localDate, 1, :now)
        """,
            nativeQuery = true
    )
    int insertFirstUse(@Param("userId") Long userId, @Param("localDate") LocalDate localDate, @Param("now") Instant now);

    /** row 存在時才走這個：used_count < limit 才能 +1，否則 0 rows */
    @Modifying
    @Query(
            value = """
        UPDATE usage_counters
        SET used_count = used_count + 1,
            updated_at_utc = :now
        WHERE user_id = :userId
          AND local_date = :localDate
          AND used_count < :limit
        """,
            nativeQuery = true
    )
    int incrementIfBelowLimit(@Param("userId") Long userId, @Param("localDate") LocalDate localDate,
                              @Param("limit") int limit, @Param("now") Instant now);

    @Query(
            value = "SELECT used_count FROM usage_counters WHERE user_id = :userId AND local_date = :localDate",
            nativeQuery = true
    )
    Integer findUsedCount(@Param("userId") Long userId, @Param("localDate") LocalDate localDate);
}
