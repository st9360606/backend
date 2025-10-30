package com.calai.backend.workout.job;

import com.calai.backend.userprofile.repo.UserProfileRepository;
import com.calai.backend.workout.service.WorkoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkoutRetentionJob {

    private final UserProfileRepository userProfileRepository;
    private final WorkoutService workoutService;

    // 每日 02:10 執行一次（伺服器時間）。可視需要改成 UTC cron 或加上 zone。
    @Scheduled(cron = "0 10 2 * * *")
    public void purgeAllUsersDaily() {
        int page = 0;
        Page<UserProfileRepository.UserTimezoneView> batch;
        do {
            batch = userProfileRepository.findAllUserTimezones(PageRequest.of(page, 1000));
            batch.forEach(v -> {
                ZoneId zone = resolveZoneOrUTC(v.getTimezone());
                try {
                    workoutService.purgeOldSessionsPublic(v.getId(), zone);
                } catch (Exception ex) {
                    log.warn("Retention purge failed for user {} (tz={}): {}",
                            v.getId(), v.getTimezone(), ex.getMessage());
                }
            });
            page++;
        } while (batch.hasNext());
    }

    private ZoneId resolveZoneOrUTC(String tz) {
        try {
            return (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("UTC");
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
