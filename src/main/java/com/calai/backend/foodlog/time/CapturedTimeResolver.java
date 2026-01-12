 package com.calai.backend.foodlog.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class CapturedTimeResolver {

    public enum TimeSource { EXIF, DEVICE_CLOCK, SERVER_RECEIVED }

    public record Result(Instant capturedAtUtc, TimeSource source, boolean suspect) {}

    private static final Duration MAX_SKEW = Duration.ofDays(30);

    public Result resolve(Instant exifUtc, Instant deviceUtc, Instant serverReceivedUtc) {
        Objects.requireNonNull(serverReceivedUtc, "serverReceivedUtc");

        Candidate c1 = candidate(exifUtc, TimeSource.EXIF, serverReceivedUtc);
        if (c1 != null) return new Result(c1.instant, c1.source, c1.suspect);

        Candidate c2 = candidate(deviceUtc, TimeSource.DEVICE_CLOCK, serverReceivedUtc);
        if (c2 != null) return new Result(c2.instant, c2.source, c2.suspect);

        return new Result(serverReceivedUtc, TimeSource.SERVER_RECEIVED, false);
    }

    private Candidate candidate(Instant utc, TimeSource src, Instant serverReceivedUtc) {
        if (utc == null) return null;

        Duration skew = Duration.between(serverReceivedUtc, utc).abs();
        if (skew.compareTo(MAX_SKEW) > 0) {
            // 超過 30 天：直接降級 SERVER_RECEIVED，並標 suspect=true
            return new Candidate(serverReceivedUtc, TimeSource.SERVER_RECEIVED, true);
        }
        return new Candidate(utc, src, false);
    }

    private record Candidate(Instant instant, TimeSource source, boolean suspect) {}
}
