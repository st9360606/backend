package com.calai.backend.referral.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MembershipSummaryServiceTrialDaysLeftTest {

    @Test
    void calcDaysLeft_shouldReturn3_whenTwoDaysAndAlmost24HoursLeft() {
        Instant now = Instant.parse("2026-05-05T08:00:01Z");
        Instant end = Instant.parse("2026-05-08T08:00:00Z");

        int daysLeft = calcDaysLeftForTest(now, end);

        assertEquals(3, daysLeft);
    }

    @Test
    void calcDaysLeft_shouldReturn2_whenOneDayAndAlmost24HoursLeft() {
        Instant now = Instant.parse("2026-05-06T08:00:01Z");
        Instant end = Instant.parse("2026-05-08T08:00:00Z");

        int daysLeft = calcDaysLeftForTest(now, end);

        assertEquals(2, daysLeft);
    }

    @Test
    void calcDaysLeft_shouldReturn1_whenLessThanOneDayLeft() {
        Instant now = Instant.parse("2026-05-07T12:00:00Z");
        Instant end = Instant.parse("2026-05-08T08:00:00Z");

        int daysLeft = calcDaysLeftForTest(now, end);

        assertEquals(1, daysLeft);
    }

    @Test
    void calcDaysLeft_shouldReturn0_whenExpired() {
        Instant now = Instant.parse("2026-05-08T08:00:00Z");
        Instant end = Instant.parse("2026-05-08T08:00:00Z");

        int daysLeft = calcDaysLeftForTest(now, end);

        assertEquals(0, daysLeft);
    }

    private static int calcDaysLeftForTest(Instant now, Instant end) {
        if (end == null || !end.isAfter(now)) {
            return 0;
        }

        long seconds = Duration.between(now, end).getSeconds();
        return (int) Math.max(1, Math.ceil(seconds / 86_400.0));
    }
}