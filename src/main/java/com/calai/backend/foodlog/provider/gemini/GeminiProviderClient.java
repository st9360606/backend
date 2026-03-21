package com.calai.backend.foodlog.provider.gemini;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.provider.gemini.spi.GeminiModeProcessor;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.provider.spi.ProviderClient;

import java.util.List;

/**
 * Gemini 總入口。
 * 注意：
 * - 這個類別只做 mode dispatch
 * - 不包含 PHOTO / ALBUM 流程
 * - 不包含 LABEL 流程
 */
public class GeminiProviderClient implements ProviderClient {

    private final List<GeminiModeProcessor> processors;

    public GeminiProviderClient(List<GeminiModeProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    @Override
    public String providerCode() {
        return "GEMINI";
    }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        String method = (entity == null) ? null : entity.getMethod();

        for (GeminiModeProcessor processor : processors) {
            if (processor.supports(method)) {
                return processor.process(entity, storage);
            }
        }

        throw new IllegalStateException("UNSUPPORTED_METHOD_FOR_GEMINI_PROVIDER");
    }
}
