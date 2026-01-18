package com.calai.backend.gemini;

import com.calai.backend.auth.entity.AuthProvider;
import com.calai.backend.users.profile.entity.UserProfile;
import com.calai.backend.users.profile.repo.UserProfileRepository;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserProfilePersistableTest {

    @Autowired UserRepo userRepo;
    @Autowired UserProfileRepository profileRepo;

    @Test
    void save_with_assigned_id_should_insert_not_stale() {
        // 1) 先有 user（@MapsId + FK 常見需要）
        User u = TestUsers.newUser();
        u = userRepo.saveAndFlush(u);

        // 2) profile 綁 user（@MapsId：profile.userId 會跟 user.id 同步）
        UserProfile p = new UserProfile();
        p.setUser(u);

        // ✅ 可寫可不寫；寫了更直覺
        p.setUserId(u.getId());

        p.setWeightKg(70.0);

        UserProfile saved = profileRepo.saveAndFlush(p);

        assertThat(saved.getUserId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(u.getId());
        assertThat(profileRepo.findById(saved.getUserId())).isPresent();
    }

    /** ✅ 測試專用的 user builder：確保必填欄位都有值 */
    static class TestUsers {
        static User newUser() {
            User u = new User();

            // ✅ users.email NOT NULL（也常是 UNIQUE）
            u.setEmail("test+" + System.nanoTime() + "@example.com");

            // ✅ 你的 enum 只有 GOOGLE/EMAIL/APPLE，所以用其中一個
            u.setProvider(AuthProvider.EMAIL);

            // ✅ 若你的 entity 沒有 @PrePersist 或欄位沒 default，就手動塞
            //    若已經有 default/PrePersist，重複 set 也不會壞
            u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());

            // 其他欄位若允許 null 可不填
            u.setName("test-user");

            return u;
        }
    }
}
