package com.calai.backend.foodlog.time;

import com.calai.backend.foodlog.model.TimeSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 決策順序：
 * 1. EXIF
 * 2. DEVICE_CLOCK
 * 3. SERVER_RECEIVED
 * 若候選時間與 serverReceivedUtc 差距 > 30 天，視為不可信。
 * - 若 EXIF 不可信，但 DEVICE_CLOCK 可信，應改用 DEVICE_CLOCK
 * - 若兩者都不可信，才退回 SERVER_RECEIVED，並 suspect=true
 */
@Component
public final class CapturedTimeResolver {

    public record Result(Instant capturedAtUtc, TimeSource source, boolean suspect) {}

    private static final Duration MAX_SKEW = Duration.ofDays(30);

    public Result resolve(Instant exifUtc, Instant deviceUtc, Instant serverReceivedUtc) {
        Objects.requireNonNull(serverReceivedUtc, "serverReceivedUtc");

        Candidate exif = candidate(exifUtc, TimeSource.EXIF, serverReceivedUtc);
        if (exif != null && exif.accepted()) {
            return new Result(exif.instant(), exif.source(), false);
        }

        Candidate device = candidate(deviceUtc, TimeSource.DEVICE_CLOCK, serverReceivedUtc);
        if (device != null && device.accepted()) {
            // EXIF 壞掉但 device 正常，仍視為可接受；suspect 可保留 true 代表上游時間來源有異常
            boolean suspect = exif != null && exif.suspect();
            return new Result(device.instant(), device.source(), suspect);
        }

        boolean suspect = (exif != null && exif.suspect()) || (device != null && device.suspect());
        return new Result(serverReceivedUtc, TimeSource.SERVER_RECEIVED, suspect);
    }

    private Candidate candidate(Instant utc, TimeSource src, Instant serverReceivedUtc) {
        if (utc == null) return null;

        Duration skew = Duration.between(serverReceivedUtc, utc).abs();
        if (skew.compareTo(MAX_SKEW) > 0) {
            return new Candidate(serverReceivedUtc, src, false, true);
        }

        return new Candidate(utc, src, true, false);
    }

    private record Candidate(
            Instant instant,
            TimeSource source,
            boolean accepted,
            boolean suspect
    ) {}
}
