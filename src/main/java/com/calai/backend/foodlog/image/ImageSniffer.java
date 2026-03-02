package com.calai.backend.foodlog.image;

import java.io.IOException;
import java.io.PushbackInputStream;

public final class ImageSniffer {

    private ImageSniffer() {}

    public enum ImageType {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png"),
        WEBP("image/webp", ".webp");

        private final String contentType;
        private final String ext;

        ImageType(String contentType, String ext) {
            this.contentType = contentType;
            this.ext = ext;
        }

        public String contentType() {
            return contentType;
        }

        public String ext() {
            return ext;
        }
    }

    public record Detection(ImageType type) {
        public String contentType() {
            return type.contentType();
        }

        public String ext() {
            return type.ext();
        }
    }

    private static final byte[] PNG_SIG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final byte[] RIFF_SIG = new byte[] {
            0x52, 0x49, 0x46, 0x46 // "RIFF"
    };

    private static final byte[] WEBP_SIG = new byte[] {
            0x57, 0x45, 0x42, 0x50 // "WEBP"
    };

    /**
     * 讀取最多 16 bytes 做 magic number 判斷，然後 unread 回去，
     * 不影響後續 storage.save() 讀取完整檔案。
     *
     * 支援：
     * - PNG
     * - JPEG
     * - WebP
     *
     * 暫不支援：
     * - HEIC / HEIF（建議 App 端先轉 JPEG）
     */
    public static Detection detect(PushbackInputStream in) throws IOException {
        byte[] head = new byte[16];
        int n = in.read(head);
        if (n <= 0) {
            return null;
        }

        // push back：後續 storage.save() 要能讀到完整檔案
        in.unread(head, 0, n);

        // PNG：前 8 bytes signature
        if (matchesAt(head, n, 0, PNG_SIG)) {
            return new Detection(ImageType.PNG);
        }

        // JPEG：FF D8 FF
        if (n >= 3
                && head[0] == (byte) 0xFF
                && head[1] == (byte) 0xD8
                && head[2] == (byte) 0xFF) {
            return new Detection(ImageType.JPEG);
        }

        // WebP：
        // bytes 0..3  = "RIFF"
        // bytes 8..11 = "WEBP"
        if (matchesAt(head, n, 0, RIFF_SIG) && matchesAt(head, n, 8, WEBP_SIG)) {
            return new Detection(ImageType.WEBP);
        }

        return null;
    }

    /**
     * 檢查 buf 從 offset 開始是否符合 prefix。
     */
    private static boolean matchesAt(byte[] buf, int n, int offset, byte[] prefix) {
        if (n < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (buf[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
