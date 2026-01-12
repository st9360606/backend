package com.calai.backend.foodlog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaDayKeyLikeTest {

    @Test
    void serverNow_plus_tz_to_localDate() {
        Instant serverNow = Instant.parse("2026-01-12T16:30:00Z"); // 台灣 2026-01-13 00:30
        ZoneId tz = ZoneId.of("Asia/Taipei");

        LocalDate local = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();
        assertThat(local.toString()).isEqualTo("2026-01-13");
    }
}
