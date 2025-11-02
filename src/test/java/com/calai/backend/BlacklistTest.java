package com.calai.backend;

import com.calai.backend.workout.nlp.Blacklist;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistTest {

    @Test
    void duplicateKeysAreMerged_notThrow() {
        // 只要能觸發類別載入就足以驗證不會因 static map 重複 key 當掉
        assertTrue(Blacklist.sizeOfLang("it") > 0);
        assertTrue(Blacklist.sizeOfLang("nl") > 0);
    }

    @Test
    void containsBad_examples() {
        assertTrue(Blacklist.containsBad("Use my promo code ABC123", "en"));
        assertTrue(Blacklist.containsBad("oferta limitada!!!", "es"));
        assertTrue(Blacklist.containsBad("codice promo", "it"));
        assertFalse(Blacklist.containsBad("45分 running", "zh-TW"));
    }
}
