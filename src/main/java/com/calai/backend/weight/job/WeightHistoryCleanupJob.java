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
            with ranked as (
               select id, user_id, log_date,
                      row_number() over (partition by user_id order by log_date desc) as rn
               from weight_history
               where log_date < current_date
            )
            delete from weight_history h
            using ranked r
            where h.id = r.id and r.rn > 7
        """).executeUpdate();
    }
}
