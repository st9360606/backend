package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.EmailOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    @Query("""
        select e from EmailOutboxEntity e
        where e.status = 'PENDING'
          and e.retryCount < :maxRetries
        order by e.createdAtUtc asc
    """)
    List<EmailOutboxEntity> findPendingToSend(int maxRetries, Pageable pageable);
}
