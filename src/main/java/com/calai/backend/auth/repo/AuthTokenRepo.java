package com.calai.backend.auth.repo;


import com.calai.backend.auth.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthTokenRepo extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);

    @Modifying
    @Query("""
      update AuthToken t
         set t.revoked = true,
             t.expiresAt = :now
       where t.userId = :userId
         and t.revoked = false
    """)
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);// 可用在全域登出
}