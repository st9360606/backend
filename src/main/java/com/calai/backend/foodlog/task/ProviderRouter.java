package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ProviderRouter {

    private final Map<String, ProviderClient> mapByCode;
    private final String defaultProvider;

    public ProviderRouter(
            Map<String, ProviderClient> springInjectedMap,
            @Value("${app.foodlog.provider:STUB}") String defaultProvider
    ) {
        this.defaultProvider = norm(defaultProvider);

        // ✅ 關鍵：把「beanName -> client」轉成「providerCode -> client」
        Map<String, ProviderClient> m = new HashMap<>();
        for (ProviderClient c : springInjectedMap.values()) {
            String code = norm(c.providerCode());
            if (code == null) continue;

            ProviderClient prev = m.putIfAbsent(code, c);
            if (prev != null) {
                throw new IllegalStateException(
                        "DUPLICATE_PROVIDER_CODE: " + code
                        + ", prev=" + prev.getClass().getName()
                        + ", dup=" + c.getClass().getName()
                );
            }
        }
        this.mapByCode = Collections.unmodifiableMap(m);

        log.info("ProviderRouter initialized. defaultProvider={}, available={}",
                this.defaultProvider, this.mapByCode.keySet());
    }

    public ProviderClient pick(FoodLogEntity log) {
        // 1) 先用每筆 log 記錄的 provider（可追溯）
        String code = norm(log == null ? null : log.getProvider());
        ProviderClient c = (code == null) ? null : mapByCode.get(code);
        if (c != null) return c;

        // 2) fallback：用 app.foodlog.provider
        c = mapByCode.get(defaultProvider);
        if (c != null) return c;

        // 3) 最後保底：STUB
        c = mapByCode.get("STUB");
        if (c != null) return c;

        // ✅ 這是配置錯誤：不該重試 5 次
        throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    public ProviderClient pickStrict(FoodLogEntity log) {
        String code = norm(log == null ? null : log.getProvider());
        if (code != null) {
            ProviderClient c = mapByCode.get(code);
            if (c != null) return c;
            // ✅ 不存在就直接炸（不要 fallback）
            throw new IllegalStateException("PROVIDER_NOT_AVAILABLE");
        }
        return pick(log);
    }
}
