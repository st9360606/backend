package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.time.CapturedTimeResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class CapturedTimeResolverTest {

    private final CapturedTimeResolver r = new CapturedTimeResolver();

    @Test
    void exifWins_whenValid() {
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant exif = Instant.parse("2026-01-13T23:00:00Z");
        Instant device = Instant.parse("2026-01-13T22:00:00Z");

        var out = r.resolve(exif, device, server);

        assertEquals(exif, out.capturedAtUtc());
        assertEquals(TimeSource.EXIF, out.source());
        assertFalse(out.suspect());
    }

    @Test
    void tooSkewed_exifDowngradesToServer_andSuspectTrue() {
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant exif = Instant.parse("2025-01-01T00:00:00Z"); // 超過 30 天
        Instant device = null;

        var out = r.resolve(exif, device, server);

        assertEquals(server, out.capturedAtUtc());
        assertEquals(TimeSource.SERVER_RECEIVED, out.source());
        assertTrue(out.suspect());
    }

    @Test
    void deviceUsed_whenExifMissing() {
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant device = Instant.parse("2026-01-13T22:00:00Z");

        var out = r.resolve(null, device, server);

        assertEquals(device, out.capturedAtUtc());
        assertEquals(TimeSource.DEVICE_CLOCK, out.source());
        assertFalse(out.suspect());
    }

    @Test
    void prefer_exif_when_ok() {
        CapturedTimeResolver r = new CapturedTimeResolver();
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant exif = Instant.parse("2026-01-13T23:50:00Z");

        var out = r.resolve(exif, null, server);
        assertEquals(exif, out.capturedAtUtc());
        assertEquals(TimeSource.EXIF, out.source());
        assertFalse(out.suspect());
    }

    @Test
    void exif_too_skewed_fallback_to_server_and_mark_suspect() {
        CapturedTimeResolver r = new CapturedTimeResolver();
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant exif = server.minus(40, ChronoUnit.DAYS);

        var out = r.resolve(exif, null, server);
        assertEquals(server, out.capturedAtUtc());
        assertEquals(TimeSource.SERVER_RECEIVED, out.source());
        assertTrue(out.suspect());
    }

    @Test
    void use_device_when_no_exif() {
        CapturedTimeResolver r = new CapturedTimeResolver();
        Instant server = Instant.parse("2026-01-14T00:00:00Z");
        Instant device = Instant.parse("2026-01-14T00:00:10Z");

        var out = r.resolve(null, device, server);
        assertEquals(device, out.capturedAtUtc());
        assertEquals(TimeSource.DEVICE_CLOCK, out.source());
        assertFalse(out.suspect());
    }

    @Test
    void fallback_server_when_all_missing() {
        CapturedTimeResolver r = new CapturedTimeResolver();
        Instant server = Instant.parse("2026-01-14T00:00:00Z");

        var out = r.resolve(null, null, server);
        assertEquals(server, out.capturedAtUtc());
        assertEquals(TimeSource.SERVER_RECEIVED, out.source());
        assertFalse(out.suspect());
    }
}
