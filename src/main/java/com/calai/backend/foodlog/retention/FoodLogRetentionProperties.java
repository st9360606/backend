package com.calai.backend.foodlog.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.retention.foodlog")
public class FoodLogRetentionProperties {

    /** DRAFT/PENDING/FAILED 保留多久（預設 3 天） */
    private Duration keepDraft = Duration.ofDays(3);

    /** SAVED 保留多久（預設 32 天） */
    private Duration keepSaved = Duration.ofDays(32);

    /** 每次掃描處理幾筆（避免單次太久） */
    private int batchSize = 200;

    /** 只在 prod 開啟（MVP 可用 enabled 控制） */
    private boolean enabled = true;

    /** （可選）DELETED 後多久做 purge/脫敏 */
    private Duration purgeAfter = Duration.ofDays(7);

    // getters/setters
    public Duration getKeepDraft() { return keepDraft; }
    public void setKeepDraft(Duration keepDraft) { this.keepDraft = keepDraft; }

    public Duration getKeepSaved() { return keepSaved; }
    public void setKeepSaved(Duration keepSaved) { this.keepSaved = keepSaved; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getPurgeAfter() { return purgeAfter; }
    public void setPurgeAfter(Duration purgeAfter) { this.purgeAfter = purgeAfter; }
}
