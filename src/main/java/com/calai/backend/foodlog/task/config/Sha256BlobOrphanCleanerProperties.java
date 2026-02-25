package com.calai.backend.foodlog.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * application.yml:
 * app.storage.local.sha256-orphan-cleaner.
 * 用於清理正式 blob 區 user-blobs/sha256 中的孤兒檔案
 * DB 沒 row
 * 或 DB ref_count <= 0
 * 建議先 dry-run=true 觀察 log，再切到 false。
 */

@Component
@ConfigurationProperties(prefix = "app.storage.local.sha256-orphan-cleaner")
public class Sha256BlobOrphanCleanerProperties {

    /**
     * 一鍵開關（預設關閉，比較安全）
     */
    private boolean enabled = false;

    /**
     * 乾跑模式：true=只記 log 不刪檔；false=真的刪
     */
    private boolean dryRun = true;

    /**
     * sha256 子路徑（相對於每個 user-* 目錄）
     * 例：base=./data，userDir=./data/user-1，sha256Subdir=blobs/sha256
     * 最終掃描：./data/user-1/blobs/sha256
     */
    private String sha256Subdir = "blobs/sha256";

    /**
     * 最小檔案年齡（避免剛寫入/剛搬移就被檢查）
     * 正式 blob 理論上很快就會有 DB row，但保守起見仍加一道保險。
     */
    private Duration minAge = Duration.ofHours(1);

    /**
     * 每次排程執行的固定延遲
     */
    private Duration fixedDelay = Duration.ofHours(6);

    /**
     * 啟動後第一次執行延遲
     */
    private Duration initialDelay = Duration.ofMinutes(5);

    /**
     * 單次最多刪除幾個檔案（避免誤刪放大）
     */
    private int maxDeletePerRun = 100;

    /**
     * 掃描最大深度（保留彈性，未來若 sha256 分片子目錄也可用）
     */
    private int maxDepth = 8;

    /**
     * 是否刪除空資料夾（不會刪 sha256 root 本身）
     */
    private boolean deleteEmptyDirs = false;

    // ===== getters/setters =====

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getSha256Subdir() {
        return sha256Subdir;
    }

    public void setSha256Subdir(String sha256Subdir) {
        this.sha256Subdir = sha256Subdir;
    }

    public Duration getMinAge() {
        return minAge;
    }

    public void setMinAge(Duration minAge) {
        this.minAge = minAge;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getMaxDeletePerRun() {
        return maxDeletePerRun;
    }

    public void setMaxDeletePerRun(int maxDeletePerRun) {
        this.maxDeletePerRun = maxDeletePerRun;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public boolean isDeleteEmptyDirs() {
        return deleteEmptyDirs;
    }

    public void setDeleteEmptyDirs(boolean deleteEmptyDirs) {
        this.deleteEmptyDirs = deleteEmptyDirs;
    }
}