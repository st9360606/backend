package com.calai.backend.workout.job;

import com.calai.backend.BackendApplication;
import com.calai.backend.workout.entity.WorkoutAliasEvent;
import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.repo.WorkoutAliasEventRepo;
import com.calai.backend.workout.repo.WorkoutAliasRepo;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修正點：
 * - 改用 IntStream.rangeClosed 讓 uid 成為 lambda 參數 (effectively final)。
 * - 或者你也可以用傳統 for 迴圈，完全避開 lambda。
 */
@SpringBootTest(classes = BackendApplication.class)
class AutoAliasPromotionJobIT {

    @Autowired AutoAliasPromotionJob job;
    @Autowired WorkoutAliasEventRepo eventRepo;
    @Autowired WorkoutAliasRepo aliasRepo;
    @Autowired WorkoutDictionaryRepo dictRepo;

    @Test
    void should_promote_when_thresholds_met() {
        final String lang = "th";
        final String phrase = "เวทเทรนนิ่ง"; // 範例片語

        // 準備一個字典（保證存在）
        final WorkoutDictionary dict = dictRepo.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WorkoutDictionary found"));

        // 清殘留，避免互相污染
        aliasRepo.findAnyByLangAndPhrase(lang, phrase)
                .ifPresent(a -> aliasRepo.deleteById(a.getId()));

        // 生成事件資料：
        // 三位不同 user（10,11,12），每人 3 筆非 generic、score=0.95 → 共 9 筆
        // 這樣可滿足 minUsers=3, minCount=7, minMedian=0.88 的預設門檻
        IntStream.rangeClosed(10, 12).forEach(uid -> {
            for (int i = 0; i < 3; i++) {
                WorkoutAliasEvent e = new WorkoutAliasEvent();
                e.setUserId((long) uid);       // ← 這裡 uid 是 lambda 參數，本身 effectively final
                e.setLangTag(lang);
                e.setPhraseLower(phrase);
                e.setMatchedDict(dict);
                e.setScore(0.95);
                e.setUsedGeneric(false);
                e.setCreatedAt(Instant.now().minusSeconds(3600));
                eventRepo.save(e);
            }
        });

        // 額外加兩筆 generic（不計分）
        for (int i = 0; i < 2; i++) {
            WorkoutAliasEvent e = new WorkoutAliasEvent();
            e.setUserId(99L);
            e.setLangTag(lang);
            e.setPhraseLower(phrase);
            e.setMatchedDict(dict);
            e.setScore(0.2);
            e.setUsedGeneric(true);
            e.setCreatedAt(Instant.now());
            eventRepo.save(e);
        }

        // 執行批次
        job.run();

        var opt = aliasRepo.findAnyByLangAndPhrase(lang, phrase);
        assertThat(opt).isPresent();

        var a = opt.get();
        assertThat(a.getStatus()).isEqualTo("APPROVED");
        assertThat(a.getDictionary()).isNotNull();
        assertThat(a.getDistinctUsers()).isGreaterThanOrEqualTo(3);
        assertThat(a.getTotalCount()).isGreaterThanOrEqualTo(7);
        assertThat(a.getConfidenceMedian()).isGreaterThanOrEqualTo(0.88);
        assertThat(a.getLastSeen()).isNotNull();
    }
}
