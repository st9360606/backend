package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface FoodLogRepository extends JpaRepository<FoodLogEntity, String> {
    Optional<FoodLogEntity> findByIdAndUserId(String id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FoodLogEntity f where f.id = :id")
    FoodLogEntity findByIdForUpdate(@Param("id") String id);

    Optional<FoodLogEntity> findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
            Long userId, String imageSha256, java.util.List<FoodLogStatus> status
    );

    @Query("""
        select f from FoodLogEntity f
        where f.userId = :userId
          and f.status = :status
          and f.capturedLocalDate >= :from
          and f.capturedLocalDate <= :to
        order by f.capturedAtUtc desc
    """)
    Page<FoodLogEntity> findByUserIdAndStatusAndCapturedLocalDateRange(
            @Param("userId") Long userId,
            @Param("status") FoodLogStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable
    );

    @Query("""
        select count(f) from FoodLogEntity f
        where f.userId = :userId
          and f.imageObjectKey = :objectKey
          and f.status <> :deletedStatus
    """)
    long countLiveRefsByObjectKey(
            @Param("userId") Long userId,
            @Param("objectKey") String objectKey,
            @Param("deletedStatus") FoodLogStatus deletedStatus
    );

    @Query(
            value = """
    SELECT *
    FROM food_logs
    WHERE status IN (:statuses)
      AND server_received_at_utc <= :cutoff
    ORDER BY server_received_at_utc ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """,
            nativeQuery = true
    )
    List<FoodLogEntity> claimExpiredForUpdate(
            @Param("statuses") java.util.List<String> statuses,
            @Param("cutoff") java.time.Instant cutoff,
            @Param("limit") int limit
    );

}
