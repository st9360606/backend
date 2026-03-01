package com.calai.backend.foodlog.mapper;

import com.calai.backend.foodlog.model.ClientAction;
import com.calai.backend.foodlog.task.FoodLogWarning;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * - TRY_LABEL：條碼查不到 / 條碼有商品但缺營養時，引導改拍營養標示（LABEL）
 * - CHECK_NETWORK：網路/timeout 類問題，提示檢查網路後重試
 * - RETAKE_PHOTO：影像品質/內容不符，提示重拍
 * - ENTER_MANUALLY：AI 無法判斷或低信心，提示手動輸入
 * - RETRY_LATER：限流/冷卻/超額/反濫用等，提示稍後再試
 * - CONTACT_SUPPORT：後端設定/授權/系統配置問題，提示聯繫客服
 */
@Service
public class ClientActionMapper {

    /**
     * 針對 FAILED errorCode 決定 App 端下一步行動
     */
    public ClientAction fromErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return ClientAction.RETRY_LATER;
        }

        String code = errorCode.trim().toUpperCase();

        return switch (code) {

            // ===== BARCODE =====
            case "BARCODE_NOT_FOUND",
                 "BARCODE_NUTRITION_UNAVAILABLE" -> ClientAction.TRY_LABEL;

            case "BARCODE_LOOKUP_FAILED" -> ClientAction.CHECK_NETWORK;

            // ===== RATE LIMIT / QUOTA / COOLDOWN =====
            case "COOLDOWN_ACTIVE",
                 "PROVIDER_RATE_LIMITED",
                 "OVER_QUOTA",
                 "OUT_OF_QUOTA",
                 "ABUSE",
                 "ABUSE_DETECTED" -> ClientAction.RETRY_LATER;

            // ===== NETWORK =====
            case "PROVIDER_TIMEOUT",
                 "PROVIDER_NETWORK_ERROR" -> ClientAction.CHECK_NETWORK;

            // ===== USER FIXABLE =====
            case "SERVING_SIZE_UNKNOWN",
                 "NO_LABEL_DETECTED",
                 "NO_FOOD_DETECTED",
                 "BLURRY_IMAGE" -> ClientAction.RETAKE_PHOTO;

            // ===== SYSTEM / PROVIDER / CONFIG =====
            case "PROVIDER_BAD_REQUEST",
                 "PROVIDER_BAD_RESPONSE",
                 "PROVIDER_AUTH_FAILED",
                 "GEMINI_API_KEY_MISSING",
                 "PROVIDER_NOT_AVAILABLE" -> ClientAction.CONTACT_SUPPORT;

            // ===== BLOCK / SAFETY / OTHER =====
            case "PROVIDER_BLOCKED" -> ClientAction.RETRY_LATER;

            default -> ClientAction.RETRY_LATER;
        };
    }

    /**
     * （可選）針對 DRAFT warnings 做建議行動
     */
    public ClientAction fromWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return ClientAction.RETRY_LATER;
        }

        if (containsIgnoreCase(warnings, FoodLogWarning.SERVING_SIZE_UNKNOWN.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.NO_LABEL_DETECTED.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.NO_FOOD_DETECTED.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.NON_FOOD_SUSPECT.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.BLURRY_IMAGE.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.LABEL_PARTIAL.name())) {
            return ClientAction.RETAKE_PHOTO;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.UNKNOWN_FOOD.name())) {
            return ClientAction.ENTER_MANUALLY;
        }
        if (containsIgnoreCase(warnings, FoodLogWarning.LOW_CONFIDENCE.name())) {
            return ClientAction.ENTER_MANUALLY;
        }

        return ClientAction.RETRY_LATER;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        if (list == null || target == null) return false;
        for (String s : list) {
            if (s == null) continue;
            if (s.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
