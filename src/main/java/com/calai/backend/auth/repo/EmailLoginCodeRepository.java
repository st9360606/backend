package com.calai.backend.auth.repo;

import com.calai.backend.auth.email.EmailLoginCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailLoginCodeRepository extends JpaRepository<EmailLoginCode, Long> {

    /**
     * 只取最新一筆有效碼（避免 NonUniqueResultException）
     */
    Optional<EmailLoginCode> findFirstByEmailAndPurposeAndConsumedAtIsNullAndExpiresAtGreaterThanEqualOrderByIdDesc(
            String email,
            String purpose,
            Instant now
    );

    /**
     * 發送新碼前，先把所有仍有效的舊碼標記為 consumed，避免同時存在多筆有效碼
     */
    @Modifying
    @Query("""
           update EmailLoginCode c
              set c.consumedAt = :now
            where c.email = :email
              and c.purpose = :purpose
              and c.consumedAt is null
              and c.expiresAt >= :now
           """)
    int consumeAllActive(@Param("email") String email,
                         @Param("purpose") String purpose,
                         @Param("now") Instant now);
}
