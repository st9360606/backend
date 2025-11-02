// src/main/java/com/calai/backend/workout/job/AutoAliasPromotionJob.java
package com.calai.backend.workout.job;

import com.calai.backend.workout.entity.WorkoutAlias;
import com.calai.backend.workout.entity.WorkoutAliasEvent;
import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.repo.WorkoutAliasEventRepo;
import com.calai.backend.workout.repo.WorkoutAliasRepo;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

//執行頻率：@Scheduled(fixedDelayString="PT72H") → 每次跑完後等 72 小時再啟動下一次（首次啟動延遲 initialDelay=5m）。沒有時區概念，以 JVM 系統時間為準。
//它不刪資料：這個 Job 不做刪除，只會「查近 30 天事件 → 判斷門檻 → upsert 到 workout_alias」。
//資料視窗：windowDays=30 → 只統計 近 30 天 的 workout_alias_event。
//升級門檻（全部成立才升級）：
//不使用 generic 的事件且有分數；
//近 30 天 distinct users ≥ minUsers(預設 3)；
//近 30 天 total events ≥ minCount(預設 7)；
//近 30 天 score 中位數 ≥ minMedian(預設 0.88)。
//指向哪個字典：取該片語近 30 天中 matched_dict_id 出現次數最多者。
//競態保護：先查是否已存在（任何狀態），有則就地升級，Insert 若撞到 UNIQUE 例外，改成更新（確保單一 (lang, phrase)）。

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoAliasPromotionJob {

    private final WorkoutAliasEventRepo eventRepo;
    private final WorkoutAliasRepo aliasRepo;
    private final WorkoutDictionaryRepo dictRepo;

    @Value("${alias.promotion.minUsers:3}")  private int minUsers;
    @Value("${alias.promotion.minCount:7}")  private int minCount;
    @Value("${alias.promotion.minMedian:0.88}") private double minMedian;
    @Value("${alias.promotion.windowDays:30}")  private int windowDays;


     // 精準 72 小時輪詢；避免 cron 的「每月 1 號為基準 */3 天」語意。
    // 注意：fixedDelay 以「上次結束」為基準，若一次運行很久，下一次會順延（合理）。

    @Scheduled(fixedDelayString = "${alias.promotion.fixedDelay:PT72H}",
            initialDelayString = "${alias.promotion.initialDelay:PT5M}")
    @Async("aliasPromotionExecutor")
    @Transactional
    public void run() {
        final Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        final var events = eventRepo.findSince(since);
        if (events.isEmpty()) {
            log.info("AutoAliasPromotion: no events since {}", since);
            return;
        }

        Map<Key, List<WorkoutAliasEvent>> byKey = events.stream()
                .collect(Collectors.groupingBy(e -> new Key(e.getLangTag(), e.getPhraseLower())));

        int promoted = 0, updated = 0;

        for (var entry : byKey.entrySet()) {
            Key key = entry.getKey();
            List<WorkoutAliasEvent> list = entry.getValue();

            List<Double> scores = list.stream()
                    .filter(e -> !e.isUsedGeneric() && e.getScore() != null)
                    .map(WorkoutAliasEvent::getScore).sorted().toList();
            if (scores.isEmpty()) continue;

            var users = list.stream().map(WorkoutAliasEvent::getUserId).collect(Collectors.toSet());
            int total = list.size();
            double median = median(scores);
            Instant lastSeen = list.stream().map(WorkoutAliasEvent::getCreatedAt)
                    .max(Instant::compareTo).orElse(Instant.now());

            if (users.size() < minUsers || total < minCount || median < minMedian) continue;

            Long dictId = list.stream()
                    .filter(e -> e.getMatchedDict() != null)
                    .collect(Collectors.groupingBy(e -> e.getMatchedDict().getId(), Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (dictId == null) continue;

            var dict = dictRepo.findById(dictId).orElse(null);
            if (dict == null) continue;

            var any = aliasRepo.findAnyByLangAndPhrase(key.lang, key.phrase);
            if (any.isPresent()) {
                var a = any.get();
                a.setStatus("APPROVED");
                a.setDictionary(dict);
                a.setTotalCount(maxSafe(a.getTotalCount(), total));
                a.setDistinctUsers(maxSafe(a.getDistinctUsers(), users.size()));
                a.setConfidenceMedian(median);
                a.setLastSeen(lastSeen);
                aliasRepo.save(a);
                updated++;
            } else {
                var a = new WorkoutAlias();
                a.setLangTag(key.lang);
                a.setPhraseLower(key.phrase);
                a.setDictionary(dict);
                a.setStatus("APPROVED");
                a.setTotalCount(total);
                a.setDistinctUsers(users.size());
                a.setConfidenceMedian(median);
                a.setLastSeen(lastSeen);
                try {
                    aliasRepo.save(a);
                    promoted++;
                } catch (DataIntegrityViolationException ex) {
                    aliasRepo.findAnyByLangAndPhrase(key.lang, key.phrase).ifPresent(b -> {
                        b.setStatus("APPROVED");
                        b.setDictionary(dict);
                        b.setTotalCount(maxSafe(b.getTotalCount(), total));
                        b.setDistinctUsers(maxSafe(b.getDistinctUsers(), users.size()));
                        b.setConfidenceMedian(median);
                        b.setLastSeen(lastSeen);
                        aliasRepo.save(b);
                    });
                    updated++;
                }
            }
        }

        log.info("AutoAliasPromotion: promoted={}, updated={}, windowDays={}, thresholds(u={},c={},m={})",
                promoted, updated, windowDays, minUsers, minCount, minMedian);
    }

    static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 1) return sorted.getFirst();
        if ((n & 1) == 1) return sorted.get(n / 2);
        double a = sorted.get(n / 2 - 1), b = sorted.get(n / 2);
        return (a + b) / 2.0;
    }
    static Integer maxSafe(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }
    private record Key(String lang, String phrase) {}
}
