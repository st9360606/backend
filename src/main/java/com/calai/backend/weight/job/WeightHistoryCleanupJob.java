package com.calai.backend.weight.job;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 僅清理 weight_history：每用戶保留今天以外最新 7 筆 */
@Component
public class WeightHistoryCleanupJob {
    private final EntityManager em;
    public WeightHistoryCleanupJob(EntityManager em) { this.em = em; }

    @Scheduled(cron = "0 30 2 * * *") // 每天 02:30
    @Transactional
    public void cleanup() {
        // Postgres：以窗口函數計算序號，刪除序號>7
        em.createNativeQuery("""
            WITH ranked AS (
                SELECT id, user_id, log_date,
                               ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY log_date DESC) AS rn
                        FROM weight_history
                        WHERE log_date < CURRENT_DATE
                    )
                    DELETE h
                    FROM weight_history h
                    JOIN ranked r ON h.id = r.id
                    WHERE r.rn > 7;
        """).executeUpdate();
    }
}
