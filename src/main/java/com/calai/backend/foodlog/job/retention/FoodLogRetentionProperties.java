package com.calai.backend.foodlog.job.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.retention.foodlog")
public class FoodLogRetentionProperties {

    /** 原始圖片最多保留多久（預設 3 天） */
    private Duration keepOriginalImage = Duration.ofDays(3);

    /** SAVED 狀態原始圖片最多保留多久（預設 15 天） */
    private Duration keepSavedOriginalImage = Duration.ofDays(15);

    /** PENDING 保留 2 天 */
    private Duration keepPending = Duration.ofDays(2);

    /** FAILED 保留 7 天 */
    private Duration keepFailed = Duration.ofDays(7);

    /** DRAFT 保留 15 天 */
    private Duration keepDraft = Duration.ofDays(15);

    /** DELETED tombstone 保留 10 天 */
    private Duration keepDeletedTombstone = Duration.ofDays(10);

    /** 每次掃描處理幾筆（避免單次太久） */
    private int batchSize = 200;

    /** 是否啟用 retention worker */
    private boolean enabled = true;
}
