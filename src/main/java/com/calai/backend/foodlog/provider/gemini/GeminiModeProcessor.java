package com.calai.backend.foodlog.provider.gemini;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.provider.spi.ProviderClient;

/**
 * Gemini 各模式處理器共用介面。
 *
 * 目的：
 * 1. 把 PHOTO / ALBUM 與 LABEL 斷開
 * 2. GeminiProviderClient 只做 dispatch，不承載任一模式流程
 */
public interface GeminiModeProcessor {

    /**
     * 是否支援此 method，例如 PHOTO / ALBUM / LABEL。
     */
    boolean supports(String method);

    /**
     * 執行該模式的完整處理流程。
     */
    ProviderClient.ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception;
}
