package com.calai.backend.foodlog.provider.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 只有在 app.provider.gemini.enabled=true 時才建立的 Spring Component。
 *
 * 用途：
 * - 避免重複寫 @Component + @ConditionalOnProperty(...)
 * - 統一 Gemini 相關元件的啟動條件
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@ConditionalOnProperty(
        prefix = "app.provider.gemini",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public @interface GeminiEnabledComponent {
}
