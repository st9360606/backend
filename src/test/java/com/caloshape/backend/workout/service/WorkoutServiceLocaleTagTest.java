package com.caloshape.backend.workout.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkoutServiceLocaleTagTest {

    @ParameterizedTest
    @CsvSource({
            "en-US, en",
            "en_GB, en",
            "es-MX, es",
            "es-ES, es",
            "in-ID, id",
            "id-ID, id",
            "iw-IL, he",
            "he-IL, he",
            "pt-BR, pt-BR",
            "pt-PT, pt-PT",
            "zh-CN, zh-CN",
            "zh-SG, zh-CN",
            "zh-HK, zh-HK",
            "zh-MO, zh-HK",
            "zh-TW, zh-TW",
            "de-DE, de",
            "fr-CA, fr",
            "ja-JP, ja"
    })
    void should_normalize_profile_locale_to_alias_locale(String raw, String expected) {
        assertEquals(expected, WorkoutService.normalizeAliasLocaleTag(raw));
    }

    @Test
    void should_fallback_to_english_for_missing_or_invalid_locale() {
        assertEquals("en", WorkoutService.normalizeAliasLocaleTag(null));
        assertEquals("en", WorkoutService.normalizeAliasLocaleTag(""));
        assertEquals("en", WorkoutService.normalizeAliasLocaleTag("und"));
    }
}
