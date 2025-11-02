// src/main/java/com/calai/backend/workout/job/WorkoutAliasEventPurgeJob.java
package com.calai.backend.workout.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

//每天清一次、保留最近 45 天，而不是「每 45 天刪一次
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkoutAliasEventPurgeJob {

    private final JdbcTemplate jdbc;

    @Value("${alias.events.purge.enabled:true}") private boolean enabled;
    @Value("${alias.events.purge.retentionDays:30}") private int retentionDays;
    @Value("${alias.events.purge.batchSize:50000}") private int batchSize;

    // 新增：每次 run 的刪除總上限 + 批次之間暫停毫秒
    @Value("${alias.events.purge.maxTotalPerRun:1000000}") private int maxTotalPerRun;
    @Value("${alias.events.purge.pauseMsBetweenBatches:50}") private long pauseMsBetweenBatches;

    /** 每日 04:10（台北時間），避免與其他任務撞時間 */
    @Scheduled(cron = "${alias.events.purge.cron:0 10 4 * * *}", zone = "Asia/Taipei")
    @Async("aliasPurgeExecutor")
    public void purge() {
        if (!enabled) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int total = 0;

        while (true) {
            int n = jdbc.update("""
                DELETE e FROM workout_alias_event e
                JOIN (
                    SELECT id FROM workout_alias_event
                    WHERE created_at < ?
                    ORDER BY id
                    LIMIT ?
                ) d ON d.id = e.id
            """, ps -> {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                ps.setInt(2, batchSize);
            });

            total += n;
            if (n < batchSize || total >= maxTotalPerRun) break;

            try { Thread.sleep(pauseMsBetweenBatches); } catch (InterruptedException ignore) {}
        }

        log.info("AliasEvent purge: cutoff={}, deleted={}, retentionDays={}, batchSize={}, maxTotalPerRun={}",
                cutoff, total, retentionDays, batchSize, maxTotalPerRun);
    }

    // 便於單元測試
    static boolean shouldStop(int deletedSoFar, int lastBatch, int batchSize, int maxTotal) {
        return lastBatch < batchSize || deletedSoFar >= maxTotal;
    }
}
