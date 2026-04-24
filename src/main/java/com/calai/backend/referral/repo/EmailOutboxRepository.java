package com.calai.backend.referral.repo;

import com.calai.backend.referral.entity.EmailOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, Long> {
    boolean existsByDedupeKey(String dedupeKey);
}
