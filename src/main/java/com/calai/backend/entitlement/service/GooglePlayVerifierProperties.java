package com.calai.backend.entitlement.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.google.play")
public class GooglePlayVerifierProperties {

    /** true 才啟用 Google Play token 驗證 */
    private boolean enabled = false;

    /** Play Console packageName */
    private String packageName;

    /** service account json 檔路徑 */
    private String serviceAccountJsonPath;

    /**
     * 只允許 dev 環境使用。
     *
     * true 時，後端會接受 App FakeBillingGateway 產生的：
     * fake-dev-sub::{productId}::{trial|paid}::{timestamp}
     *
     * prod 絕對不可開。
     */
    private boolean devFakeTokensEnabled = false;
}
