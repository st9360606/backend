package com.caloshape.backend.users.profile.service;

import com.caloshape.backend.users.user.repo.UserRepo;
import com.caloshape.backend.users.profile.dto.UpsertProfileRequest;
import com.caloshape.backend.users.profile.entity.UserProfile;
import com.caloshape.backend.users.profile.repo.UserProfileRepository;
import com.caloshape.backend.users.user.entity.User;
import com.caloshape.backend.weight.repo.WeightHistoryRepo;
import com.caloshape.backend.weight.repo.WeightTimeseriesRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceWorkoutGoalTest {

    @Mock UserProfileRepository repo;
    @Mock UserRepo users;
    @Mock WeightTimeseriesRepo weightSeries;
    @Mock WeightHistoryRepo weightHistory;

    @InjectMocks
    UserProfileService svc;

    @Test
    void upsert_shouldDefaultDailyWorkoutGoalTo450_forNewUser() {
        Long uid = 1L;
        User u = new User();
        u.setId(uid);

        when(users.findById(uid)).thenReturn(Optional.of(u));
        when(repo.findByUserId(uid)).thenReturn(Optional.empty());
        when(repo.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        UpsertProfileRequest req = new UpsertProfileRequest(
                null, null,
                null, null, null,
                null, null,
                null, null,
                null, null,
                null, null, null,
                null, null,null,
                null // dailyWorkoutGoalKcal
        );

        var dto = svc.upsert(uid, req, null, false);

        assertThat(dto.dailyWorkoutGoalKcal()).isEqualTo(450);
    }

    @Test
    void upsert_shouldClampDailyWorkoutGoal() {
        Long uid = 1L;
        User u = new User();
        u.setId(uid);

        UserProfile existing = new UserProfile();
        existing.setUser(u);
        existing.setUserId(uid);
        existing.setDailyWorkoutGoalKcal(450);

        when(users.findById(uid)).thenReturn(Optional.of(u));
        when(repo.findByUserId(uid)).thenReturn(Optional.of(existing));
        when(repo.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        UpsertProfileRequest req = new UpsertProfileRequest(
                null, null,
                null, null, null,
                null, null,
                null, null,
                null, null,
                null, null, null,
                null, null,null,
                999999 // dailyWorkoutGoalKcal
        );

        var dto = svc.upsert(uid, req, null, false);

        assertThat(dto.dailyWorkoutGoalKcal()).isEqualTo(20000);
    }
}
