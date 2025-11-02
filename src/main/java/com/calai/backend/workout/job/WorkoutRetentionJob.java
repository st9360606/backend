// src/main/java/com/calai/backend/workout/job/WorkoutRetentionJob.java
package com.calai.backend.workout.job;

import com.calai.backend.userprofile.repo.UserProfileRepository;
import com.calai.backend.workout.service.WorkoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.ZoneId;


//頻率：@Scheduled(cron = "0 20 4 * * *", zone = "Asia/Taipei") ⇒ 每天台北時間 04:20 觸發一次；@Async("retentionExecutor") 讓它在自訂執行緒池非阻塞執行。
//掃描方式：每次抓 1000 筆使用者時區分頁處理，直到掃完。
//刪除條件：對每位使用者，取其 個人時區 的「今天 00:00」，再往回 7 天 得到 cutoff；刪除該使用者 started_at < cutoff 的 workout_session。
//等價於：保留最近 7 個「完整在地日」+ 今天（不會刪到今天）。
//時區容錯：使用者沒填或填錯時區 → fallback 為 ZoneId.systemDefault()（依伺服器配置而定）。

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkoutRetentionJob {

    private final UserProfileRepository userRepo;
    private final WorkoutService workoutService;

    /** 每日 04:20（台北時間），依使用者時區計算 T-7 */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Taipei")
    @Async("retentionExecutor")
    public void run() {
        int pageSize = 1000;
        int page = 0;
        while (true) {
            Page<UserProfileRepository.UserTimezoneView> pg =
                    userRepo.findAllUserTimezones(PageRequest.of(page, pageSize));
            if (pg.isEmpty()) break;

            pg.forEach(view -> {
                Long uid = view.getId();
                String tz = view.getTimezone();
                ZoneId zone = nullSafeZone(tz);
                try {
                    workoutService.purgeOldSessionsPublic(uid, zone);
                } catch (Exception ex) {
                    log.warn("Retention purge failed for user={} zone={}: {}", uid, zone, ex.toString());
                }
            });

            if (!pg.hasNext()) break;
            page++;
        }
        log.info("WorkoutRetentionJob finished.");
    }

    private ZoneId nullSafeZone(String tz) {
        try { return (tz == null || tz.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(tz); }
        catch (Exception e) { return ZoneId.systemDefault(); }
    }
}
