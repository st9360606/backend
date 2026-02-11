package com.calai.backend.foodlog.quota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.abuse-guard")
public class AbuseGuardProperties {

    /** 總開關（dev 可關閉） */
    private boolean enabled = true;

    /** 統計視窗（預設 1 小時） */
    private Duration window = Duration.ofHours(1);

    /** 視窗內操作次數門檻 */
    private int hourOpsThreshold = 50;

    /** 視窗內 cache hit rate 低於此值就判定可疑 */
    private double minCacheHitRate = 0.10;

    /** 視窗內同 deviceId 切換帳號數量超過此值就判定可疑 */
    private int deviceSwitchThreshold = 3;

    /** ABUSE 冷卻時間（429 Retry-After） */
    private Duration cooldown = Duration.ofMinutes(30);

    /** 強制降級（forceLowUntilUtc）時間 */
    private Duration forceLow = Duration.ofHours(2);

    /** in-memory key TTL（避免永遠累積） */
    private Duration keyTtl = Duration.ofHours(2);
}
