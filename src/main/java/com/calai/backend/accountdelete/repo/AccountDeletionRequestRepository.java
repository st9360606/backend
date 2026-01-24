package com.calai.backend.accountdelete.repo;

import com.calai.backend.accountdelete.entity.AccountDeletionRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequestEntity, String> {

    Optional<AccountDeletionRequestEntity> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AccountDeletionRequestEntity r where r.userId = :userId")
    Optional<AccountDeletionRequestEntity> findByUserIdForUpdate(@Param("userId") Long userId);

    @Query(value = """
      SELECT *
      FROM account_deletion_requests
      WHERE (req_status='REQUESTED')
         OR (req_status='FAILED' AND next_retry_at_utc IS NOT NULL AND next_retry_at_utc <= :now)
      ORDER BY requested_at_utc ASC
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<AccountDeletionRequestEntity> claimRunnableForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
