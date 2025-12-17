package com.calai.backend.users.profile.repo;

import com.calai.backend.users.profile.entity.UserProfile;
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
        String getTimezone();
    }

    @Query("select u.userId as id, u.timezone as timezone from UserProfile u")
    Page<UserTimezoneView> findAllUserTimezones(Pageable pageable);
}
