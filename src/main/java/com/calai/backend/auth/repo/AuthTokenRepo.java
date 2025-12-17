package com.calai.backend.auth.repo;


import com.calai.backend.auth.entity.AuthToken;
import com.calai.backend.users.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AuthTokenRepo extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);
    long deleteByUserAndType(User user, AuthToken.TokenType type); // 可用在全域登出
}