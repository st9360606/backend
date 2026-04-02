package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FoodLogRepository extends JpaRepository<FoodLogEntity, String> {

    Optional<FoodLogEntity> findByIdAndUserId(String id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FoodLogEntity f where f.id = :id")
    Optional<FoodLogEntity> findByIdForUpdate(@Param("id") String id);

    Optional<FoodLogEntity> findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
            Long userId, String imageSha256, List<FoodLogStatus> status
    );

    Optional<FoodLogEntity> findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
            Long userId,
            java.util.Collection<String> methods,
            String imageSha256,
            java.util.Collection<FoodLogStatus> statuses
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

    @Query(value = """
              SELECT *
              FROM food_logs
              WHERE user_id = :userId
                AND status IN ('PENDING','DRAFT','SAVED')
                AND server_received_at_utc >= :fromUtc
              ORDER BY server_received_at_utc DESC
              LIMIT :limit
            """, nativeQuery = true)
    List<FoodLogEntity> findRecentPreviewItems(
            @Param("userId") Long userId,
            @Param("fromUtc") Instant fromUtc,
            @Param("limit") int limit
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
            @Param("statuses") List<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );

    /**
     * 原始圖片 retention 候選：
     * - SAVED：用 savedCutoff（15 天）
     * - 非 SAVED：用 defaultCutoff（3 天）
     */
    @Query(
            value = """
                SELECT *
                FROM food_logs
                WHERE status <> 'DELETED'
                  AND image_object_key IS NOT NULL
                  AND image_object_key <> ''
                  AND (
                       (status = 'SAVED' AND server_received_at_utc <= :savedCutoff)
                    OR (status <> 'SAVED' AND server_received_at_utc <= :defaultCutoff)
                  )
                ORDER BY server_received_at_utc ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    List<FoodLogEntity> claimImageExpiredForUpdate(
            @Param("defaultCutoff") Instant defaultCutoff,
            @Param("savedCutoff") Instant savedCutoff,
            @Param("limit") int limit
    );

    /**
     * 真正要 hard purge 的 DELETED tombstone：
     * 注意要看 deleted_at_utc，不是 server_received_at_utc
     */
    @Query(
            value = """
                SELECT *
                FROM food_logs
                WHERE status = 'DELETED'
                  AND deleted_at_utc IS NOT NULL
                  AND deleted_at_utc <= :cutoff
                ORDER BY deleted_at_utc ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    List<FoodLogEntity> claimDeletedTombstonesForUpdate(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );

    @Query(value = """
              SELECT id
              FROM food_logs
              WHERE user_id = :userId
                AND (
                     status <> 'DELETED'
                  OR effective IS NOT NULL
                  OR image_object_key IS NOT NULL
                  OR image_sha256 IS NOT NULL
                )
              ORDER BY created_at_utc ASC
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<String> claimFoodLogsNeedingCleanupForUpdate(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Modifying
    @Query(value = """
              DELETE FROM food_logs
              WHERE user_id = :userId
              LIMIT :limit
            """, nativeQuery = true)
    int deleteByUserIdLimit(@Param("userId") Long userId, @Param("limit") int limit);

    @Query(value = """
              SELECT COUNT(*) FROM food_logs WHERE user_id = :userId
            """, nativeQuery = true)
    long countByUserId(@Param("userId") Long userId);

    @Query(value = """
            SELECT *
            FROM food_logs
            WHERE user_id = :userId
              AND status = 'SAVED'
              AND server_received_at_utc >= :fromUtc
            ORDER BY server_received_at_utc DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<FoodLogEntity> findSavedRecentByServerReceivedAtUtc(
            @Param("userId") Long userId,
            @Param("fromUtc") Instant fromUtc,
            @Param("limit") int limit
    );
}
