package com.calai.backend;

import com.calai.backend.workout.nlp.Blacklist;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class BlacklistTest {

    @Test
    void duplicateKeysAreMerged_notThrow() {
        // 只要能觸發類別載入就足以驗證不會因 static map 重複 key 當掉
        assertTrue(Blacklist
                .sizeOfLang("it") > 0);
        assertTrue(Blacklist.sizeOfLang("nl") > 0);
    }

    @Test
    void containsBad_examples() {
        assertTrue(Blacklist.containsBad("Use my promo code ABC123", "en"));
        assertTrue(Blacklist.containsBad("oferta limitada!!!", "es"));
        assertTrue(Blacklist.containsBad("codice promo", "it"));
        assertFalse(Blacklist.containsBad("45分 running", "zh-TW"));
    }

    @Test
    void casino_should_be_blocked_in_any_locale() {
        // 當斷詞命中會回 true；通過時不會自動印任何東西，所以手動印出來
        String[] locales = {"en", "zh-TW", "es"};
        for (String loc : locales) {
            boolean hit = Blacklist.containsBad("casino 10 min", loc);
            String why = Blacklist.matchReason("casino 10 min", loc);
            System.out.println("[Blacklist] locale=" + loc + " hit=" + hit + " reason=" + why);
            assertThat(hit).isTrue();
            assertThat(why).isNotBlank(); // 例如 "*:casino" 或 "en:casino"
        }
    }
}
