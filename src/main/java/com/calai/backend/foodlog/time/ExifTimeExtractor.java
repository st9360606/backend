package com.calai.backend.foodlog.time;

import com.calai.backend.foodlog.storage.StorageService;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;

import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 讀 EXIF：
 * - DateTimeOriginal: "yyyy:MM:dd HH:mm:ss" (tag 0x9003)
 * - OffsetTimeOriginal: "+08:00" (tag 0x9011, EXIF 2.31)
 *
 * 若 EXIF 沒有 offset：用 clientTz 當本地時區再轉 UTC。
 *
 * ✅ 注意：有些 metadata-extractor 版本沒有 TAG_OFFSET_TIME_ORIGINAL 常數，
 * 所以這裡直接用 tag number 0x9011，避免編譯失敗。
 */
public final class ExifTimeExtractor {

    private ExifTimeExtractor() {}

    private static final DateTimeFormatter EXIF_FMT =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    // EXIF 2.31 offset tags
    private static final int TAG_OFFSET_TIME_ORIGINAL = 0x9011;

    /**
     * @param storage   你的 StorageService（可開 objectKey）
     * @param objectKey 例如 tempKey
     * @param clientTz  由 App header "X-Client-Timezone" 傳入；若 EXIF 無 offset，用它推回 UTC
     */
    public static Optional<Instant> tryReadCapturedAtUtc(
            StorageService storage,
            String objectKey,
            ZoneId clientTz
    ) {
        if (storage == null || objectKey == null || objectKey.isBlank()) return Optional.empty();
        if (clientTz == null) clientTz = ZoneOffset.UTC;

        try (InputStream in = storage.open(objectKey).inputStream()) {
            Metadata metadata = ImageMetadataReader.readMetadata(in);
            ExifSubIFDDirectory dir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (dir == null) return Optional.empty();

            // 1) DateTimeOriginal
            String dt = dir.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (dt == null || dt.isBlank()) return Optional.empty();

            // 2) OffsetTimeOriginal（可能不存在）
            String off = dir.getString(TAG_OFFSET_TIME_ORIGINAL);

            return parseExifDateTimeToInstant(dt, off, clientTz);

        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * ✅ 拆出來方便測試（不用真的 JPEG）。
     */
    static Optional<Instant> parseExifDateTimeToInstant(String exifDateTime, String offset, ZoneId clientTz) {
        if (exifDateTime == null || exifDateTime.isBlank()) return Optional.empty();
        if (clientTz == null) clientTz = ZoneOffset.UTC;

        final LocalDateTime ldt;
        try {
            ldt = LocalDateTime.parse(exifDateTime.trim(), EXIF_FMT);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }

        // 有 offset：用 offset 直接轉 UTC
        if (offset != null && !offset.isBlank()) {
            try {
                ZoneOffset zo = ZoneOffset.of(offset.trim());
                return Optional.of(OffsetDateTime.of(ldt, zo).toInstant());
            } catch (Exception ignored) {
                // offset 格式怪：降級成 clientTz
            }
        }

        // 無 offset 或 offset 不合法：用 clientTz 當地時間轉 UTC
        try {
            return Optional.of(ZonedDateTime.of(ldt, clientTz).toInstant());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
