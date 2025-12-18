// src/test/java/com/calai/backend/AutoAliasPromotionJobIT.java
package com.calai.backend;

import com.calai.backend.workout.entity.WorkoutAliasEvent;
import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.job.AutoAliasPromotionJob;
import com.calai.backend.workout.repo.WorkoutAliasEventRepo;
import com.calai.backend.workout.repo.WorkoutAliasRepo;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BackendApplication.class)
class AutoAliasPromotionJobIT {

    @Autowired AutoAliasPromotionJob job;
    @Autowired WorkoutAliasEventRepo eventRepo;
    @Autowired WorkoutAliasRepo aliasRepo;
    @Autowired WorkoutDictionaryRepo dictRepo;

    @Test
    void should_promote_when_thresholds_met() {
        final String lang = "th";

        // ✅ 每次跑都獨一無二，避免撞到舊資料/別的測試
        final String phrase = ("เวทเทรนนิ่ง-" + UUID.randomUUID()).toLowerCase();

        final WorkoutDictionary dict = dictRepo.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WorkoutDictionary found"));

        // ✅ 清乾淨：alias + events 都要清
        aliasRepo.findAnyByLangAndPhrase(lang, phrase)
                .ifPresent(a -> aliasRepo.deleteById(a.getId()));
        eventRepo.deleteByLangAndPhrase(lang, phrase);

        // ✅ 確保在 windowDays(30) 內：用 now-1h
        final Instant withinWindow = Instant.now().minusSeconds(3600);

        // 3 users (10,11,12) each 3 events, score=0.95, non-generic => 9 events
        IntStream.rangeClosed(10, 12).forEach(uid -> {
            for (int i = 0; i < 3; i++) {
                WorkoutAliasEvent e = new WorkoutAliasEvent();
                e.setUserId((long) uid);
                e.setLangTag(lang);
                e.setPhraseLower(phrase);
                e.setMatchedDict(dict);
                e.setScore(0.95);
                e.setUsedGeneric(false);
                e.setCreatedAt(withinWindow);
                eventRepo.save(e);
            }
        });

        // 2 generic events
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

        // ✅ 先 flush：避免 job 讀不到剛寫入的資料（同 DB、不同 transaction 時很常見）
        eventRepo.flush();

        // 執行批次
        job.run();

        // ✅ 若 job 內部是非同步（你 log thread 名 alias-prom-1 很像），這裡可能查太快
        // 先給你「不引入新套件」的最小等待：輪詢幾次
        var opt = java.util.Optional.<com.calai.backend.workout.entity.WorkoutAlias>empty();
        for (int i = 0; i < 20; i++) {
            opt = aliasRepo.findAnyByLangAndPhrase(lang, phrase);
            if (opt.isPresent()) break;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

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
