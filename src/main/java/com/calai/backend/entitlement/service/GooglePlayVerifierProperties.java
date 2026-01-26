// src/main/java/com/calai/backend/entitlement/service/GooglePlayVerifierProperties.java
package com.calai.backend.entitlement.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.google.play")
public class GooglePlayVerifierProperties {

    /** ✅ 開關：true 才啟用 Google Play token 驗證 */
    private boolean enabled = false;

    /** 你的 App packageName（Play Console 那個） */
    private String packageName;

    /** service account json 檔路徑（dev 可用；prod 建議 ADC） */
    private String serviceAccountJsonPath;
}
