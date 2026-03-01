package com.calai.backend.foodlog.packagedfood;

import java.util.Optional;

/**
 * 從 FOOD / ALBUM 圖片中嘗試抓條碼。
 */
public interface ImageBarcodeDetector {

    /**
     * @param imageBytes 原始圖片 bytes
     * @return normalize 後的 barcode；抓不到則 empty
     */
    Optional<String> detect(byte[] imageBytes);
}
