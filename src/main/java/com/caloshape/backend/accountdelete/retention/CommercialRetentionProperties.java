package com.caloshape.backend.accountdelete.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retention limits for the minimized records retained after account deletion.
 * These values must match the public privacy policy and Play Console disclosures.
 */
@Data
@ConfigurationProperties(prefix = "app.retention.commercial")
public class CommercialRetentionProperties {

    private boolean enabled = false;
    private int batchSize = 500;
    private Duration encryptedPurchaseTokenRetention = Duration.ofDays(180);
    private int billingAuditRetentionYears = 5;
    private int referralRewardRetentionYears = 5;
    private int standardRiskRetentionMonths = 24;
    private int deniedRiskRetentionYears = 5;
    private int deletionRequestRetentionYears = 3;
}
