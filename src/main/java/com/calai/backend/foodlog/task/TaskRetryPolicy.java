package com.calai.backend.foodlog.task;

/**
 * TaskRetryPolicy 是你 背景任務（FoodLogTaskWorker）重試的「規則表」，集中管理「最多重試幾次、每次隔多久再重試」。
 * MAX_ATTEMPTS=5 代表 最多自動嘗試 5 次，超過就不要再跑（避免無限燒錢/打爆 provider）。
 * nextDelaySec(attempts) 回傳 下一次重試要延後幾秒（2→5→10→20→40→60…）。
 * shouldGiveUp(attempts) 回傳 是否該放棄（達到上限就停止自動重試）。
 * attempts 建議用「markRunning 後的 attempts」：代表 這次是第幾次真正出手呼叫 provider。
 */
public final class TaskRetryPolicy {

    private TaskRetryPolicy() {}

    /** ✅ 超過就停止自動重試（避免燒錢） */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * 退避：2, 5, 10, 20, 40（上限 60）
     * attempts：本次「即將執行」是第幾次（markRunning 後的 attempts）
     */
    public static int nextDelaySec(int attempts) {
        if (attempts <= 1) return 2;
        if (attempts == 2) return 5;
        if (attempts == 3) return 10;
        if (attempts == 4) return 20;
        if (attempts == 5) return 40;
        return 60;
    }

    public static boolean shouldGiveUp(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
