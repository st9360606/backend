package com.calai.backend.auth.repo;

import com.calai.backend.auth.email.EmailLoginCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface EmailLoginCodeRepository extends JpaRepository<EmailLoginCode, Long> {
    @Query("""
      select c from EmailLoginCode c
      where c.email = :email and c.purpose='LOGIN'
        and c.consumedAt is null and c.expiresAt >= :now
      order by c.id desc
    """)
    Optional<EmailLoginCode> findLatestActive(String email, Instant now);
}
