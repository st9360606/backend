package com.calai.backend.workout.repo;

import com.calai.backend.workout.entity.WorkoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkoutSessionRepo extends JpaRepository<WorkoutSession, Long> {

    /**
     * 給 today() / UI list 使用：
     * 依 request 當前 timezone 算出的 start/end 去抓，維持現有 API 行為不變
     */
    @Query("""
        select ws
        from WorkoutSession ws
        join fetch ws.dictionary d
        where ws.userId = :userId
          and ws.startedAt >= :startInclusive
          and ws.startedAt < :endExclusive
        order by ws.startedAt desc
        """)
    List<WorkoutSession> findUserSessionsBetween(
            @Param("userId") Long userId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );

    @Query("""
        select ws
        from WorkoutSession ws
        where ws.id = :sessionId
          and ws.userId = :userId
        """)
    Optional<WorkoutSession> findByIdAndUserId(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId
    );

    /**
     * 給 summary 用：
     * 直接依 persisted localDate 聚合
     */
    @Query("""
        select coalesce(sum(ws.kcal), 0)
        from WorkoutSession ws
        where ws.userId = :userId
          and ws.localDate = :localDate
        """)
    Integer sumKcalByUserIdAndLocalDate(
            @Param("userId") Long userId,
            @Param("localDate") LocalDate localDate
    );

    long countByUserIdAndLocalDate(Long userId, LocalDate localDate);

    /**
     * 給 retention 用：
     * 與 summary / delete-recompute 保持同一套 local bucket 語意
     */
    @Modifying
    @Query("""
        delete from WorkoutSession ws
        where ws.userId = :userId
          and ws.localDate < :cutoffDate
        """)
    int deleteByUserIdAndLocalDateBefore(
            @Param("userId") Long userId,
            @Param("cutoffDate") LocalDate cutoffDate
    );
}
