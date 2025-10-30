package com.calai.backend.userprofile.repo;

import com.calai.backend.userprofile.entity.UserProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findByUserId(Long userId);
    boolean existsByUserId(Long userId);

    interface UserTimezoneView {
        Long getId();
        String getTimezone(); // 例如 "Asia/Taipei"，可能為 null
    }

    @Query("""
        select u.userId as id, u.locale as timezone
        from UserProfile u
        """)
    Page<UserTimezoneView> findAllUserTimezones(Pageable pageable);
}
