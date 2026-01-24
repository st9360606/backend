package com.calai.backend.users.user.repo;

import com.calai.backend.users.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepo extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    User findByIdForUpdate(@Param("id") Long id);

    Optional<User> findByGoogleSub(String googleSub);

    // Spring Data JPA 內建 IgnoreCase 關鍵字，但我們的 User#setEmail 也做了 lower-case，兩邊保險
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
