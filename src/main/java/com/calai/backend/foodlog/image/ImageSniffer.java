package com.calai.backend.foodlog.image;

import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.Arrays;

public final class ImageSniffer {

    private ImageSniffer() {}

    public enum ImageType {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png");

        private final String contentType;
        private final String ext;

        ImageType(String contentType, String ext) {
            this.contentType = contentType;
            this.ext = ext;
        }

        public String contentType() { return contentType; }
        public String ext() { return ext; }
    }

    public record Detection(ImageType type) {
        public String contentType() { return type.contentType(); }
        public String ext() { return type.ext(); }
    }

    private static final byte[] PNG_SIG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /**
     * 會讀取最多 16 bytes 作判斷，然後 push back 回去（不影響後續存檔）
     */
    public static Detection detect(PushbackInputStream in) throws IOException {
        byte[] head = new byte[16];
        int n = in.read(head);
        if (n <= 0) return null;

        // push back：後續 storage.save() 要能讀到完整檔案
        in.unread(head, 0, n);

        // PNG：前 8 bytes signature
        if (n >= 8 && startsWith(head, n, PNG_SIG)) {
            return new Detection(ImageType.PNG);
        }

        // JPEG：FF D8 FF
        if (n >= 3 && (head[0] == (byte) 0xFF) && (head[1] == (byte) 0xD8) && (head[2] == (byte) 0xFF)) {
            return new Detection(ImageType.JPEG);
        }

        return null;
    }

    private static boolean startsWith(byte[] buf, int n, byte[] prefix) {
        if (n < prefix.length) return false;
        return Arrays.equals(Arrays.copyOfRange(buf, 0, prefix.length), prefix);
    }
}
