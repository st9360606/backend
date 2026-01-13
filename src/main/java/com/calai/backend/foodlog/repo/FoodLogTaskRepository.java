package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
