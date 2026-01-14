package com.calai.backend.foodlog.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml:
 * app.storage.local.tmp-cleaner.*
 */
@ConfigurationProperties(prefix = "app.storage.local.tmp-cleaner")
public class LocalTempBlobCleanerProperties {

    /** 一鍵開關（prod 想關就關） */
    private boolean enabled = true;

    /** 保留多久（超過就刪） */
    private Duration keep = Duration.ofHours(6);

    /** 掃描最大深度（避免深層目錄拖慢） */
    private int maxDepth = 8;

    /** 固定延遲與初始延遲（@Scheduled 會用到字串 placeholder 讀這兩個 key） */
    private Duration fixedDelay = Duration.ofMinutes(10);
    private Duration initialDelay = Duration.ofMinutes(1);

    /** 是否刪空資料夾 */
    private boolean deleteEmptyDirs = true;

    /**
     * tmp 子路徑（相對於 base-dir）
     * 例：base=./data，tmpSubdir=blobs/tmp => ./data/blobs/tmp
     */
    private String tmpSubdir = "blobs/tmp";

    // ===== getters/setters =====
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getKeep() { return keep; }
    public void setKeep(Duration keep) { this.keep = keep; }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }

    public Duration getFixedDelay() { return fixedDelay; }
    public void setFixedDelay(Duration fixedDelay) { this.fixedDelay = fixedDelay; }

    public Duration getInitialDelay() { return initialDelay; }
    public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }

    public boolean isDeleteEmptyDirs() { return deleteEmptyDirs; }
    public void setDeleteEmptyDirs(boolean deleteEmptyDirs) { this.deleteEmptyDirs = deleteEmptyDirs; }

    public String getTmpSubdir() { return tmpSubdir; }
    public void setTmpSubdir(String tmpSubdir) { this.tmpSubdir = tmpSubdir; }
}
