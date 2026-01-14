package com.calai.backend.foodlog.time;

import com.calai.backend.foodlog.dto.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 決策順序：EXIF > DEVICE_CLOCK > SERVER_RECEIVED
 * 若與 serverReceivedUtc 差距 > 30 天：降級 SERVER_RECEIVED，並 suspect=true
 */
public final class CapturedTimeResolver {

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
            return new Candidate(serverReceivedUtc, TimeSource.SERVER_RECEIVED, true);
        }
        return new Candidate(utc, src, false);
    }

    private record Candidate(Instant instant, TimeSource source, boolean suspect) {}
}
