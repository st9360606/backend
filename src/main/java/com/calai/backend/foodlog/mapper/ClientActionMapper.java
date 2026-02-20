package com.calai.backend.foodlog.mapper;

import com.calai.backend.foodlog.model.ClientAction;
import com.calai.backend.foodlog.task.FoodLogWarning;
import org.springframework.stereotype.Service;

import java.util.List;
/**
 * - TRY_LABEL：條碼查不到時，引導改拍營養標示（LABEL）
 * - CHECK_NETWORK：網路/timeout 類問題，提示檢查網路後重試
 * - RETAKE_PHOTO：影像品質/內容不符，提示重拍
 * - ENTER_MANUALLY：AI 無法判斷，提示手動輸入
 * - RETRY_LATER：限流/冷卻/超額/反濫用等，提示稍後再試
 * - CONTACT_SUPPORT：後端設定/授權問題，提示聯繫客服
 */
@Service
public class ClientActionMapper {

    /**
     * 針對「FAILED errorCode」決定 App 端下一步行動
     */
    public ClientAction fromErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return ClientAction.RETRY_LATER;

        // ✅ normalize：避免大小寫不一致
        String code = errorCode.trim().toUpperCase();

        return switch (code) {

            // ===== BARCODE =====
            case "BARCODE_NOT_FOUND" -> ClientAction.TRY_LABEL;       // MVP：查不到引導拍標籤
            case "BARCODE_LOOKUP_FAILED" -> ClientAction.CHECK_NETWORK;

            // ===== RATE LIMIT / QUOTA / COOLDOWN =====
            // 你系統常見：429 / cooldown / over-quota / abuse
            case "COOLDOWN_ACTIVE",
                 "PROVIDER_RATE_LIMITED",
                 "OVER_QUOTA",
                 "OUT_OF_QUOTA",
                 "ABUSE",
                 "ABUSE_DETECTED" -> ClientAction.RETRY_LATER;

            // ===== NETWORK =====
            case "PROVIDER_TIMEOUT",
                 "PROVIDER_NETWORK_ERROR" -> ClientAction.CHECK_NETWORK;

            // ===== USER FIXABLE (通常重拍可改善) =====
            case "SERVING_SIZE_UNKNOWN",
                 "NO_LABEL_DETECTED",
                 "NO_FOOD_DETECTED",
                 "BLURRY_IMAGE",
                 "PROVIDER_BLOCKED",
                 "PROVIDER_BAD_REQUEST",
                 "PROVIDER_BAD_RESPONSE" -> ClientAction.RETAKE_PHOTO;

            // ===== SYSTEM / CONFIG =====
            case "PROVIDER_AUTH_FAILED",
                 "GEMINI_API_KEY_MISSING",
                 "PROVIDER_NOT_AVAILABLE" -> ClientAction.CONTACT_SUPPORT;

            default -> ClientAction.RETRY_LATER;
        };
    }

    /**
     * （可選）針對 DRAFT warnings 做建議行動（目前你先不一定要用）
     * ✅ 改成不分大小寫，避免 effective 回來的 warnings 大小寫不一致
     */
    public ClientAction fromWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return ClientAction.RETRY_LATER;

        if (containsIgnoreCase(warnings, FoodLogWarning.SERVING_SIZE_UNKNOWN.name())) return ClientAction.RETAKE_PHOTO;
        if (containsIgnoreCase(warnings, FoodLogWarning.NO_LABEL_DETECTED.name())) return ClientAction.RETAKE_PHOTO;
        if (containsIgnoreCase(warnings, FoodLogWarning.NO_FOOD_DETECTED.name())) return ClientAction.RETAKE_PHOTO;
        if (containsIgnoreCase(warnings, FoodLogWarning.NON_FOOD_SUSPECT.name())) return ClientAction.RETAKE_PHOTO;
        if (containsIgnoreCase(warnings, FoodLogWarning.BLURRY_IMAGE.name())) return ClientAction.RETAKE_PHOTO;
        if (containsIgnoreCase(warnings, FoodLogWarning.UNKNOWN_FOOD.name())) return ClientAction.ENTER_MANUALLY;

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
