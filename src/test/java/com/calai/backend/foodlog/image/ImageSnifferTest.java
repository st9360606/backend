package com.calai.backend.foodlog.image;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ImageSnifferTest {

    @Test
    void detect_should_return_png() throws IOException {
        byte[] bytes = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D
        };

        PushbackInputStream in = new PushbackInputStream(new ByteArrayInputStream(bytes), 16);

        ImageSniffer.Detection detection = ImageSniffer.detect(in);

        assertThat(detection).isNotNull();
        assertThat(detection.type()).isEqualTo(ImageSniffer.ImageType.PNG);
        assertThat(detection.contentType()).isEqualTo("image/png");
        assertThat(detection.ext()).isEqualTo(".png");
    }

    @Test
    void detect_should_return_jpeg() throws IOException {
        byte[] bytes = new byte[] {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
        };

        PushbackInputStream in = new PushbackInputStream(new ByteArrayInputStream(bytes), 16);

        ImageSniffer.Detection detection = ImageSniffer.detect(in);

        assertThat(detection).isNotNull();
        assertThat(detection.type()).isEqualTo(ImageSniffer.ImageType.JPEG);
        assertThat(detection.contentType()).isEqualTo("image/jpeg");
        assertThat(detection.ext()).isEqualTo(".jpg");
    }

    @Test
    void detect_should_return_webp() throws IOException {
        byte[] bytes = new byte[] {
                0x52, 0x49, 0x46, 0x46,             // RIFF
                0x24, 0x00, 0x00, 0x00,             // file size placeholder
                0x57, 0x45, 0x42, 0x50,             // WEBP
                0x56, 0x50, 0x38, 0x58              // VP8X
        };

        PushbackInputStream in = new PushbackInputStream(new ByteArrayInputStream(bytes), 16);

        ImageSniffer.Detection detection = ImageSniffer.detect(in);

        assertThat(detection).isNotNull();
        assertThat(detection.type()).isEqualTo(ImageSniffer.ImageType.WEBP);
        assertThat(detection.contentType()).isEqualTo("image/webp");
        assertThat(detection.ext()).isEqualTo(".webp");
    }

    @Test
    void detect_should_return_null_for_unknown_format() throws IOException {
        byte[] bytes = new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        };

        PushbackInputStream in = new PushbackInputStream(new ByteArrayInputStream(bytes), 16);

        ImageSniffer.Detection detection = ImageSniffer.detect(in);

        assertThat(detection).isNull();
    }

    @Test
    void detect_should_unread_bytes_back_to_stream() throws IOException {
        byte[] bytes = new byte[] {
                0x52, 0x49, 0x46, 0x46,
                0x24, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50,
                0x56, 0x50, 0x38, 0x58
        };

        PushbackInputStream in = new PushbackInputStream(new ByteArrayInputStream(bytes), 16);

        ImageSniffer.Detection detection = ImageSniffer.detect(in);
        assertThat(detection).isNotNull();
        assertThat(detection.type()).isEqualTo(ImageSniffer.ImageType.WEBP);

        byte[] readBack = in.readAllBytes();
        assertThat(Arrays.equals(readBack, bytes)).isTrue();
    }
}
