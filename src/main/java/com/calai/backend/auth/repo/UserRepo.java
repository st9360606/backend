package com.calai.backend.auth.repo;

import com.calai.backend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepo extends JpaRepository<User, Long> {
    Optional<User> findByGoogleSub(String googleSub);

    // Spring Data JPA 內建 IgnoreCase 關鍵字，但我們的 User#setEmail 也做了 lower-case，兩邊保險
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
