package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface FoodLogRequestRepository extends JpaRepository<FoodLogRequestEntity, Long> {

    @Query(
            value = """
            SELECT food_log_id
            FROM food_log_requests
            WHERE user_id = :userId AND request_id = :requestId
            """,
            nativeQuery = true
    )
    String findFoodLogId(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Query(
            value = """
            SELECT status
            FROM food_log_requests
            WHERE user_id = :userId AND request_id = :requestId
            """,
            nativeQuery = true
    )
    String findStatus(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Modifying
    @Query(
            value = """
            INSERT IGNORE INTO food_log_requests(user_id, request_id, status, created_at_utc, updated_at_utc)
            VALUES (:userId, :requestId, 'RESERVED', :now, :now)
            """,
            nativeQuery = true
    )
    int reserve(@Param("userId") Long userId, @Param("requestId") String requestId, @Param("now") Instant now);

    /** 只有 food_log_id 仍為 NULL 才能 attach（避免競態覆寫） */
    @Modifying
    @Query(
            value = """
            UPDATE food_log_requests
            SET food_log_id = :foodLogId,
                status = 'ATTACHED',
                updated_at_utc = :now
            WHERE user_id = :userId
              AND request_id = :requestId
              AND food_log_id IS NULL
            """,
            nativeQuery = true
    )
    int attach(@Param("userId") Long userId,
               @Param("requestId") String requestId,
               @Param("foodLogId") String foodLogId,
               @Param("now") Instant now);

    @Modifying
    @Query(
            value = """
            UPDATE food_log_requests
            SET status='FAILED',
                error_code=:code,
                error_message=:msg,
                updated_at_utc=:now
            WHERE user_id=:userId AND request_id=:requestId
            """,
            nativeQuery = true
    )
    int markFailed(@Param("userId") Long userId,
                   @Param("requestId") String requestId,
                   @Param("code") String code,
                   @Param("msg") String msg,
                   @Param("now") Instant now);

    /** 失敗時可選擇釋放（讓同 requestId 可重送重做） */
    @Modifying
    @Query(
            value = """
            DELETE FROM food_log_requests
            WHERE user_id=:userId AND request_id=:requestId AND food_log_id IS NULL
            """,
            nativeQuery = true
    )
    int releaseIfNotAttached(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Modifying
    @Query(
            value = """
        DELETE FROM food_log_requests
        WHERE user_id = :userId
          AND request_id = :requestId
          AND status = 'FAILED'
          AND food_log_id IS NULL
        """,
            nativeQuery = true
    )
    int deleteFailedIfNotAttached(@Param("userId") Long userId, @Param("requestId") String requestId);
}
