package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.DeletionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeletionJobRepository extends JpaRepository<DeletionJobEntity, String> {

    @Query(
            value = """
                    SELECT *
                    FROM deletion_jobs
                    WHERE
                         (job_status = 'QUEUED')
                      OR (job_status = 'FAILED'
                          AND next_retry_at_utc IS NOT NULL
                          AND next_retry_at_utc <= :now)
                    ORDER BY created_at_utc ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<DeletionJobEntity> claimRunnableForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    Optional<DeletionJobEntity> findByFoodLogId(String foodLogId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from DeletionJobEntity j where j.foodLogId = :foodLogId")
    Optional<DeletionJobEntity> findByFoodLogIdForUpdate(@Param("foodLogId") String foodLogId);


    @Query(value = """
              SELECT COUNT(*)
              FROM deletion_jobs
              WHERE user_id = :userId
                AND job_status IN ('QUEUED','RUNNING','FAILED')
            """, nativeQuery = true)
    long countOutstandingByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
              DELETE FROM deletion_jobs
              WHERE user_id = :userId
            """, nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);


}
