package com.calai.backend.accountdelete.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.account-deletion.worker")
public class AccountDeletionWorkerProperties {

    private boolean enabled = true;

    /** 一次撿幾個 user 的刪除任務 */
    private int claimLimit = 5;

    /** 每個 table 每次最多刪幾筆（避免鎖太久） */
    private int perTableDeleteLimit = 500;

    /** food_logs 每次處理幾筆 */
    private int foodBatch = 200;

    /** RUNNING 任務多久再回來檢查一次（等 deletion_jobs 跑完） */
    private Duration checkInterval = Duration.ofSeconds(30);

    /** 失敗退避（第一次 10s、第二次 30s、第三次 60s…） */
    private Duration baseRetryDelay = Duration.ofSeconds(10);

    // getters/setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getClaimLimit() { return claimLimit; }
    public void setClaimLimit(int claimLimit) { this.claimLimit = claimLimit; }

    public int getPerTableDeleteLimit() { return perTableDeleteLimit; }
    public void setPerTableDeleteLimit(int perTableDeleteLimit) { this.perTableDeleteLimit = perTableDeleteLimit; }

    public int getFoodBatch() { return foodBatch; }
    public void setFoodBatch(int foodBatch) { this.foodBatch = foodBatch; }

    public Duration getCheckInterval() { return checkInterval; }
    public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }

    public Duration getBaseRetryDelay() { return baseRetryDelay; }
    public void setBaseRetryDelay(Duration baseRetryDelay) { this.baseRetryDelay = baseRetryDelay; }
}
