package com.calai.backend.foodlog.job.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.retention.foodlog")
public class FoodLogRetentionProperties {

    /** 原始圖片最多保留多久（預設 3 天） */
    private Duration keepOriginalImage = Duration.ofDays(3);

    /** PENDING 保留 2 天 */
    private Duration keepPending = Duration.ofDays(2);

    /** FAILED 保留 7 天 */
    private Duration keepFailed = Duration.ofDays(7);

    /** DRAFT 保留 32 天 */
    private Duration keepDraft = Duration.ofDays(32);

    /** DELETED tombstone 保留 32 天（之後若要 hard purge 再用） */
    private Duration keepDeletedTombstone = Duration.ofDays(32);

    /** 每次掃描處理幾筆（避免單次太久） */
    private int batchSize = 200;

    /** 是否啟用 retention worker */
    private boolean enabled = true;
}
