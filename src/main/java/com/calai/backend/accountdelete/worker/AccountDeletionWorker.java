package com.calai.backend.accountdelete.worker;

import com.calai.backend.accountdelete.config.AccountDeletionWorkerProperties;
import com.calai.backend.accountdelete.entity.AccountDeletionRequestEntity;
import com.calai.backend.accountdelete.repo.AccountDeletionRequestRepository;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.users.user.entity.User;
import com.calai.backend.users.user.repo.UserRepo;
import com.calai.backend.accountdelete.service.UserDataPurgeDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "app.account-deletion.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountDeletionWorker {

    private final AccountDeletionWorkerProperties props;

    private final AccountDeletionRequestRepository reqRepo;
    private final UserRepo userRepo;

    private final UserDataPurgeDao purgeDao;

    private final FoodLogRepository foodLogRepo;
    private final DeletionJobRepository deletionJobRepo;
    private final ImageBlobService blobService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    @Transactional
    public void runOnce() {
        if (!props.isEnabled()) return;

        Instant now = Instant.now();
        List<AccountDeletionRequestEntity> reqs = reqRepo.claimRunnableForUpdate(now, props.getClaimLimit());
        if (reqs.isEmpty()) return;

        for (AccountDeletionRequestEntity req : reqs) {
            try {
                processOne(req, now);
            } catch (Exception e) {
                log.warn("account deletion worker failed. reqId={} userId={}", req.getId(), req.getUserId(), e);
                markFailed(req, now, e);
            }
        }
    }

    private void processOne(AccountDeletionRequestEntity req, Instant now) {
        Long userId = req.getUserId();

        // 0) 標記 RUNNING
        if (!"RUNNING".equalsIgnoreCase(req.getReqStatus())) {
            req.setReqStatus("RUNNING");
            req.setStartedAtUtc(req.getStartedAtUtc() == null ? now : req.getStartedAtUtc());
        }
        req.setAttempts(req.getAttempts() + 1);
        req.setLastError(null);
        req.setNextRetryAtUtc(now.plus(props.getCheckInterval()));
        reqRepo.save(req);

        // 1) 分批刪：與 food 無關（每次少量，跑多輪）
        int limit = props.getPerTableDeleteLimit();

        // 注意：有些表沒 user_id 就用 join（tasks/overrides）
        int delTasks = purgeDao.deleteByFoodLogFkForUser("food_log_tasks", "food_log_id", userId, limit);
        int delOverrides = purgeDao.deleteByFoodLogFkForUser("food_log_overrides", "food_log_id", userId, limit);

        int delRequests = purgeDao.deleteByUserId("food_log_requests", userId, limit);
        int delUsage = purgeDao.deleteByUserId("usage_counters", userId, limit);

        int delEnt = purgeDao.deleteByUserId("user_entitlements", userId, limit);

        int delDaily = purgeDao.deleteByUserId("user_daily_activity", userId, limit);
        int delWater = purgeDao.deleteByUserId("user_water_daily", userId, limit);
        int delFasting = purgeDao.deleteByUserId("fasting_plan", userId, limit);

        int delWorkoutSession = purgeDao.deleteByUserId("workout_session", userId, limit);
        int delAliasEvent = purgeDao.deleteByUserId("workout_alias_event", userId, limit);

        int delWeightHistory = purgeDao.deleteWeightHistoryWithPhotos(userId, limit);
        int delWeightTs = purgeDao.deleteByUserId("weight_timeseries", userId, limit);

        int delProfile = purgeDao.deleteByUserId("user_profiles", userId, 1);

        // auth_tokens：你同步階段已 revoked，這裡做 hard delete
        int delTokens = purgeDao.deleteByUserId("auth_tokens", userId, limit);

        // 2) food：把 logs 變 DELETED + 清 effective + enqueue deletion_jobs（用你的 deletion job pipeline 刪圖片）
        int foodTouched = cleanupFoodLogsAndEnqueueDeletionJobs(userId, now, props.getFoodBatch());

        // 3) 判斷是否仍有 media job 在跑
        long outstandingJobs = deletionJobRepo.countOutstandingByUserId(userId);

        // 4) 若 media 都完成：finalize（hard delete food_logs + deletion_jobs）
        if (outstandingJobs == 0) {
            finalizeHardDeleteFoodAndJobs(userId, limit);

            // 檢查是否還有 image_blobs 殘留（理論上應為 0）
            boolean blobLeft = purgeDao.existsAnyByUserId("image_blobs", userId);
            if (blobLeft) {
                // 先不硬 fail：多等幾輪（DeletionJobWorker 可能慢）
                req.setNextRetryAtUtc(now.plusSeconds(30));
                req.setLastError("image_blobs still exists, waiting...");
                reqRepo.save(req);
                return;
            }

            // users tombstone：保留 row（允許同 email 回來新註冊，因為 email/google_sub 已 null）
            User u = userRepo.findByIdForUpdate(userId);
            if (u != null) {
                u.setStatus("DELETED");
                userRepo.save(u);
            }

            req.setReqStatus("DONE");
            req.setCompletedAtUtc(now);
            req.setNextRetryAtUtc(null);
            reqRepo.save(req);

            log.info("account deletion DONE. userId={} reqId={}", userId, req.getId());
            return;
        }

        log.info(
                "account deletion progress. userId={} reqId={} del[ws={},ae={},wh={},wt={},profile={},tokens={}] foodTouched={} outstandingJobs={}",
                userId, req.getId(),
                delWorkoutSession, delAliasEvent, delWeightHistory, delWeightTs, delProfile, delTokens,
                foodTouched, outstandingJobs
        );

        // 還沒完：維持 RUNNING，下一次再來
        req.setReqStatus("RUNNING");
        req.setNextRetryAtUtc(now.plus(props.getCheckInterval()));
        reqRepo.save(req);
    }

    private int cleanupFoodLogsAndEnqueueDeletionJobs(Long userId, Instant now, int batch) {
        List<String> ids = foodLogRepo.claimFoodLogsNeedingCleanupForUpdate(userId, batch);
        if (ids.isEmpty()) return 0;

        List<FoodLogEntity> logs = foodLogRepo.findAllById(ids);

        int touched = 0;
        for (FoodLogEntity f : logs) {

            // enqueue deletion job（只有有 sha 才需要）
            String sha = f.getImageSha256();
            String objectKey = f.getImageObjectKey();

            if (sha != null && !sha.isBlank()) {
                // 避免重複 enqueue：已有 job 就跳過（你 repo 已有 findByFoodLogId）
                if (deletionJobRepo.findByFoodLogId(f.getId()).isEmpty()) {

                    String ext = blobService.findExtOrNull(userId, sha);

                    DeletionJobEntity job = new DeletionJobEntity();
                    job.setFoodLogId(f.getId());
                    job.setUserId(userId);
                    job.setSha256(sha);
                    job.setExt(ext);
                    job.setImageObjectKey(objectKey);
                    job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
                    job.setAttempts(0);
                    job.setNextRetryAtUtc(null);
                    deletionJobRepo.save(job);
                }
            }

            // 軟刪 + 清除結果/指標（你要求：照片+結果要刪）
            f.setStatus(FoodLogStatus.DELETED);
            f.setDeletedBy("ACCOUNT_DELETE");
            f.setDeletedAtUtc(now);

            f.setEffective(null);
            f.setLastErrorCode(null);
            f.setLastErrorMessage(null);

            // ✅ 進一步去識別：把 image refs 清掉（job 已保存所需資訊）
            f.setImageObjectKey(null);
            f.setImageSha256(null);
            f.setImageContentType(null);
            f.setImageSizeBytes(null);

            foodLogRepo.save(f);
            touched++;
        }

        return touched;
    }

    private void finalizeHardDeleteFoodAndJobs(Long userId, int limit) {
        // 先刪 deletion_jobs（FK 指向 food_logs）
        deletionJobRepo.deleteAllByUserId(userId);

        // 再硬刪 food_logs（分批）
        int loops = 0;
        while (loops < 50) {
            int n = foodLogRepo.deleteByUserIdLimit(userId, limit);
            if (n <= 0) break;
            loops++;
        }
    }

    private void markFailed(AccountDeletionRequestEntity req, Instant now, Exception e) {
        req.setReqStatus("FAILED");
        req.setLastError((e.getMessage() == null || e.getMessage().isBlank()) ? e.getClass().getSimpleName() : e.getMessage());

        Duration base = props.getBaseRetryDelay();
        int attempts = Math.max(1, req.getAttempts());

        long sec = switch (Math.min(attempts, 5)) {
            case 1 -> base.getSeconds();
            case 2 -> 30;
            case 3 -> 60;
            case 4 -> 180;
            default -> 300;
        };

        req.setNextRetryAtUtc(now.plusSeconds(sec));
        reqRepo.save(req);
    }
}
