package com.calai.backend.foodlog.packagedfood;

import com.calai.backend.foodlog.barcode.BarcodeNormalizer;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ✅ P2-1 強化版：
 * 1. 原圖 + 多個裁切區域
 * 2. 90 / 180 / 270 旋轉再重試
 * 3. 每張圖先 Hybrid，再 GlobalHistogram
 * 注意：
 * - 找不到條碼（NotFound）屬正常情況，不特別記 log
 * - 只對真正異常情況記 debug，避免 log flood
 */
@Component
@Slf4j
public class ZxingImageBarcodeDetector implements ImageBarcodeDetector {

    private final Map<DecodeHintType, Object> hints;

    public ZxingImageBarcodeDetector() {
        Map<DecodeHintType, Object> m = new EnumMap<>(DecodeHintType.class);
        m.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        m.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        m.put(DecodeHintType.POSSIBLE_FORMATS, List.of(
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.ITF
        ));
        this.hints = m;
    }

    @Override
    public Optional<String> detect(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }

        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) {
                log.debug("zxing detect: ImageIO.read returned null");
                return Optional.empty();
            }

            for (BufferedImage img : variants(src)) {
                Optional<String> found = tryDecode(img);
                if (found.isPresent()) {
                    return found;
                }
            }

            return Optional.empty();

        } catch (Exception ex) {
            log.debug("zxing detect unexpected failure: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> tryDecode(BufferedImage image) {
        MultiFormatReader reader = new MultiFormatReader();

        Optional<String> hybrid = decodeWithHybrid(reader, image);
        if (hybrid.isPresent()) {
            return hybrid;
        }

        Optional<String> histogram = decodeWithGlobalHistogram(reader, image);
        if (histogram.isPresent()) {
            return histogram;
        }

        return Optional.empty();
    }

    private Optional<String> decodeWithHybrid(MultiFormatReader reader, BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image))
            );
            Result result = reader.decode(bitmap, hints);
            return Optional.ofNullable(normalize(result.getText()));

        } catch (NotFoundException ex) {
            return Optional.empty();

        } catch (Exception ex) {
            log.debug("zxing hybrid decode unexpected failure: {}", ex.getMessage());
            return Optional.empty();

        } finally {
            reader.reset();
        }
    }

    private Optional<String> decodeWithGlobalHistogram(MultiFormatReader reader, BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                    new GlobalHistogramBinarizer(new BufferedImageLuminanceSource(image))
            );
            Result result = reader.decode(bitmap, hints);
            return Optional.ofNullable(normalize(result.getText()));

        } catch (NotFoundException ex) {
            return Optional.empty();

        } catch (Exception ex) {
            log.debug("zxing global-histogram decode unexpected failure: {}", ex.getMessage());
            return Optional.empty();

        } finally {
            reader.reset();
        }
    }

    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return BarcodeNormalizer.normalizeOrThrow(raw).normalized();
        } catch (Exception ignore) {
            // fallback: 移除非數字再試一次
        }

        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 8) {
            return null;
        }

        try {
            return BarcodeNormalizer.normalizeOrThrow(digits).normalized();
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * ✅ P2-1：
     * - 原圖
     * - 多區域 crop
     * - 再對 90/180/270 旋轉圖做同樣策略
     */
    private List<BufferedImage> variants(BufferedImage src) {
        List<BufferedImage> out = new ArrayList<>();

        addVariantsForBase(out, src);

        BufferedImage r90 = rotate(src, 90);
        if (r90 != null) addVariantsForBase(out, r90);

        BufferedImage r180 = rotate(src, 180);
        if (r180 != null) addVariantsForBase(out, r180);

        BufferedImage r270 = rotate(src, 270);
        if (r270 != null) addVariantsForBase(out, r270);

        return out;
    }

    private void addVariantsForBase(List<BufferedImage> out, BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        out.add(src);

        // 中央大區
        out.add(cropSafe(src, w / 10, h / 10, w * 8 / 10, h * 8 / 10));
        out.add(cropSafe(src, w * 2 / 10, h * 2 / 10, w * 6 / 10, h * 6 / 10));

        // 左右 / 上下半部
        out.add(cropSafe(src, 0, h / 2, w, h / 2));       // bottom half
        out.add(cropSafe(src, 0, 0, w, h / 2));           // top half
        out.add(cropSafe(src, w / 2, 0, w / 2, h));       // right half
        out.add(cropSafe(src, 0, 0, w / 2, h));           // left half

        // 底部偏重：包裝條碼常在下半區
        out.add(cropSafe(src, 0, h * 2 / 3, w, h / 3));
        out.add(cropSafe(src, 0, h * 3 / 4, w, h / 4));
        out.add(cropSafe(src, w / 10, h * 6 / 10, w * 8 / 10, h * 3 / 10));

        // 底部中央細帶：很多條碼在下方中間附近
        out.add(cropSafe(src, w / 5, h * 3 / 5, w * 3 / 5, h * 2 / 5));
    }

    private BufferedImage rotate(BufferedImage src, int degrees) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();

            double rad = Math.toRadians(degrees);

            int newW = (degrees == 90 || degrees == 270) ? h : w;
            int newH = (degrees == 90 || degrees == 270) ? w : h;

            BufferedImage dst = new BufferedImage(
                    newW,
                    newH,
                    src.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : src.getType()
            );

            Graphics2D g2d = dst.createGraphics();
            try {
                AffineTransform at = new AffineTransform();
                at.translate(newW / 2.0, newH / 2.0);
                at.rotate(rad);
                at.translate(-w / 2.0, -h / 2.0);
                g2d.drawImage(src, at, null);
            } finally {
                g2d.dispose();
            }

            return dst;
        } catch (Exception ex) {
            log.debug("zxing rotate failed degrees={} msg={}", degrees, ex.getMessage());
            return null;
        }
    }

    private BufferedImage cropSafe(BufferedImage src, int x, int y, int w, int h) {
        int sx = Math.max(0, x);
        int sy = Math.max(0, y);
        int sw = Math.min(w, src.getWidth() - sx);
        int sh = Math.min(h, src.getHeight() - sy);

        if (sw <= 0 || sh <= 0) {
            return src;
        }
        return src.getSubimage(sx, sy, sw, sh);
    }
}
