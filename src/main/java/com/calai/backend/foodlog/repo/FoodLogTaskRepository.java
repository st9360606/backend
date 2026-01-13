package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FoodLogTaskRepository extends JpaRepository<FoodLogTaskEntity, String> {

    /**
     * ✅ MySQL 8：用 FOR UPDATE SKIP LOCKED 領取任務，避免多 worker 重複撿同一筆
     * 注意：
     * - 需要在 @Transactional 內呼叫才會真的 lock
     * - SKIP LOCKED 讓其他 worker 不會卡住，會跳過已被鎖住的 rows
     */
    @Query(
            value = """
            SELECT *
            FROM food_log_tasks
            WHERE
                 (task_status = 'QUEUED')
              OR (task_status = 'FAILED'
                  AND next_retry_at_utc IS NOT NULL
                  AND next_retry_at_utc <= :now)
            ORDER BY created_at_utc ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
            nativeQuery = true
    )
    List<FoodLogTaskEntity> claimRunnableForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    Optional<FoodLogTaskEntity> findByFoodLogId(String foodLogId);

    /** ✅ Step 3.8：手動 retry / worker 競態時需要 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from FoodLogTaskEntity t where t.foodLogId = :foodLogId")
    Optional<FoodLogTaskEntity> findByFoodLogIdForUpdate(@Param("foodLogId") String foodLogId);

    /**
     * ✅ Step 3.8：回收卡死的 RUNNING
     * 把 RUNNING 且 updated_at_utc 太久沒動的，轉為 FAILED 並可立即重跑
     */
    @Modifying
    @Query(
            value = """
            UPDATE food_log_tasks
            SET task_status = 'FAILED',
                next_retry_at_utc = :now,
                last_error_code = :code,
                last_error_message = :msg,
                updated_at_utc = :now
            WHERE task_status = 'RUNNING'
              AND updated_at_utc <= :staleBefore
            """,
            nativeQuery = true
    )
    int resetStaleRunning(
            @Param("staleBefore") Instant staleBefore,
            @Param("now") Instant now,
            @Param("code") String code,
            @Param("msg") String msg
    );
}
