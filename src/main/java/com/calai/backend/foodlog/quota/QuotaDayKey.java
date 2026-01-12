 package com.calai.backend.foodlog.quota;

import java.time.*;

public final class QuotaDayKey {

    private QuotaDayKey() {}

    public static LocalDate todayLocalDate(Instant serverNowUtc, ZoneId userTz) {
        return ZonedDateTime.ofInstant(serverNowUtc, userTz).toLocalDate();
    }
}
