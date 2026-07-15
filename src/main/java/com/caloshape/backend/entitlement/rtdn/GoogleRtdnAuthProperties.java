package com.caloshape.backend.entitlement.rtdn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.google.play.rtdn")
public class GoogleRtdnAuthProperties {

    /** Verify Google-signed Pub/Sub OIDC tokens from the Authorization header. */
    private boolean oidcEnabled;

    /** Exact audience configured on the Pub/Sub push subscription. */
    private String oidcAudience;

    /** Exact service-account email configured on the Pub/Sub push subscription. */
    private String oidcServiceAccountEmail;

    /** Local/dev compatibility only. Production must keep this disabled. */
    private boolean legacyInternalTokenEnabled;
}
