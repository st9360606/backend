package com.calai.backend.weight.jobs;

import com.calai.backend.common.storage.LocalImageStorage;
import com.calai.backend.weight.entity.WeightHistory;
import com.calai.backend.weight.repo.WeightHistoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class WeightPhotoCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(WeightPhotoCleanupJob.class);

    private final WeightHistoryRepo history;
    private final LocalImageStorage images;

    public WeightPhotoCleanupJob(WeightHistoryRepo history, LocalImageStorage images) {
        this.history = history;
        this.images = images;
    }

    /** ✅ 系統啟動完成後先清一次 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        runCleanupSafe("startup");
    }

    /** ✅ 之後每 72 小時清一次 */
    @Scheduled(fixedDelayString = "259200000", initialDelayString = "60000") // 72h, 啟動 60s 後開始排程
    public void every3Days() {
        runCleanupSafe("scheduled");
    }

    private void runCleanupSafe(String reason) {
        try {
            int deleted = cleanupRetention();
            log.info("[weight-photo-cleanup] reason={}, deleted={}", reason, deleted);
        } catch (Exception e) {
            log.warn("[weight-photo-cleanup] reason={}, failed={}", reason, e.toString(), e);
        }
    }

    /**
     * 清理邏輯：
     * 1) 每個 userId 僅保留最新 7 筆(依 logDate desc) 的 photoUrl
     * 2) 其餘：刪檔 + DB photoUrl 設為 null
     * 3) 刪孤兒檔（磁碟有、DB 無引用）
     */
    @Transactional
    public int cleanupRetention() {
        int deletedCount = 0;

        List<Long> uids = history.findUserIdsWithPhotos();
        for (Long uid : uids) {
            // 先取要保留的 7 筆
            List<WeightHistory> keep = history.findPhotosByUserDesc(uid, PageRequest.of(0, 7));
            Set<String> keepUrls = keep.stream()
                    .map(WeightHistory::getPhotoUrl)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // 從第 8 筆開始分頁刪（避免一次撈爆）
            int page = 1;
            int pageSize = 200;

            while (true) {
                List<WeightHistory> chunk = history.findPhotosByUserDesc(uid, PageRequest.of(page, pageSize));
                if (chunk.isEmpty()) break;

                for (WeightHistory row : chunk) {
                    String url = row.getPhotoUrl();
                    if (url == null) continue;

                    // 保險：如果剛好還在 keep 裡就跳過（理論上不會）
                    if (keepUrls.contains(url)) continue;

                    images.deleteByUrlQuietly(url);
                    row.setPhotoUrl(null);     // ✅ 避免前端破圖
                    deletedCount++;
                }
                history.saveAll(chunk);
                page++;
            }
        }

        // ✅ 刪孤兒檔：磁碟存在但 DB 已無引用
        Set<String> referenced = history.findAllPhotoUrls().stream()
                .flatMap(url -> images.filenameFromUrl(url).stream())
                .collect(Collectors.toSet());

        Set<String> onDisk = images.listAllFilenames();
        for (String fn : onDisk) {
            if (!referenced.contains(fn)) {
                images.deleteByUrlQuietly(images.urlFromFilename(fn));
                deletedCount++;
            }
        }

        return deletedCount;
    }
}
