package com.calai.backend.foodlog;

import com.calai.backend.foodlog.image.ImageSniffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PushbackInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ImageSnifferTest {

    @Test
    void detect_png() throws Exception {
        byte[] png = new byte[] {(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A, 1,2,3,4};
        var in = new PushbackInputStream(new ByteArrayInputStream(png), 16);

        var det = ImageSniffer.detect(in);

        assertNotNull(det);
        assertEquals(ImageSniffer.ImageType.PNG, det.type());
        assertEquals("image/png", det.contentType());
        assertEquals(".png", det.ext());
    }

    @Test
    void detect_jpeg() throws Exception {
        byte[] jpg = new byte[] {(byte)0xFF,(byte)0xD8,(byte)0xFF, 0x00, 0x11, 0x22};
        var in = new PushbackInputStream(new ByteArrayInputStream(jpg), 16);

        var det = ImageSniffer.detect(in);

        assertNotNull(det);
        assertEquals(ImageSniffer.ImageType.JPEG, det.type());
        assertEquals("image/jpeg", det.contentType());
        assertEquals(".jpg", det.ext());
    }

    @Test
    void reject_unknown() throws Exception {
        byte[] bad = new byte[] {0x01,0x02,0x03,0x04};
        var in = new PushbackInputStream(new ByteArrayInputStream(bad), 16);

        var det = ImageSniffer.detect(in);

        assertNull(det);
    }
}
