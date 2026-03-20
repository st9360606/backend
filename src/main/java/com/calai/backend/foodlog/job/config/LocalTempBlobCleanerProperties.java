package com.calai.backend.foodlog.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml:
 * app.storage.local.tmp-cleaner.*
 */
@Data
@ConfigurationProperties(prefix = "app.storage.local.tmp-cleaner")
public class LocalTempBlobCleanerProperties {

    // ===== getters/setters =====
    /** 一鍵開關（prod 想關就關） */
    private boolean enabled = true;

    /** 保留多久（超過就刪檔；空資料夾可提前刪） */
    private Duration keep = Duration.ofHours(6);

    /** 掃描最大深度（避免深層目錄拖慢） */
    private int maxDepth = 8;

    /** 固定延遲與初始延遲（@Scheduled 會用到字串 placeholder 讀這兩個 key） */
    private Duration fixedDelay = Duration.ofMinutes(10);
    private Duration initialDelay = Duration.ofMinutes(1);

    /** 是否刪空資料夾 */
    private boolean deleteEmptyDirs = true;

    /**
     * tmp 子路徑（相對於每個 user 目錄）
     * 例：base=./data，userDir=./data/user-1，tmpSubdir=blobs/tmp
     * 最終掃描：./data/user-1/blobs/tmp
     */
    private String tmpSubdir = "blobs/tmp";

}

