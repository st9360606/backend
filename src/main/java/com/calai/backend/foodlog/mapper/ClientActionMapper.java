package com.calai.backend.foodlog.mapper;

import com.calai.backend.foodlog.dto.ClientAction;
import com.calai.backend.foodlog.task.FoodLogWarning;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClientActionMapper {

    /**
     * 針對「FAILED errorCode」決定 App 端下一步行動
     */
    public ClientAction fromErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return ClientAction.RETRY_LATER;

        return switch (errorCode) {
            case "PROVIDER_TIMEOUT", "PROVIDER_NETWORK_ERROR" -> ClientAction.CHECK_NETWORK;
            case "PROVIDER_BLOCKED", "PROVIDER_BAD_REQUEST", "PROVIDER_BAD_RESPONSE" -> ClientAction.RETAKE_PHOTO;
            case "PROVIDER_AUTH_FAILED", "GEMINI_API_KEY_MISSING", "PROVIDER_NOT_AVAILABLE" -> ClientAction.CONTACT_SUPPORT;
            default -> ClientAction.RETRY_LATER;
        };
    }

    /**
     * （可選）針對 DRAFT warnings 做建議行動（目前你先不一定要用）
     */
    public ClientAction fromWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return ClientAction.RETRY_LATER;

        if (warnings.contains(FoodLogWarning.NO_FOOD_DETECTED.name())) return ClientAction.RETAKE_PHOTO;
        if (warnings.contains(FoodLogWarning.NON_FOOD_SUSPECT.name())) return ClientAction.RETAKE_PHOTO;
        if (warnings.contains(FoodLogWarning.UNKNOWN_FOOD.name())) return ClientAction.ENTER_MANUALLY;
        if (warnings.contains(FoodLogWarning.BLURRY_IMAGE.name())) return ClientAction.RETAKE_PHOTO;

        return ClientAction.RETRY_LATER;
    }
}
