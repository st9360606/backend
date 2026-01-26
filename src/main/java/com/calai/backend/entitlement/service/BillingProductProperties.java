package com.calai.backend.entitlement.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "app.billing.products")
public class BillingProductProperties {

    private Set<String> monthly = new HashSet<>();
    private Set<String> yearly  = new HashSet<>();

    public String toTierOrNull(String productIdRaw) {
        if (productIdRaw == null) return null;
        String id = productIdRaw.trim().toLowerCase(Locale.ROOT);
        if (monthly.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(id::equals)) return "MONTHLY";
        if (yearly.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(id::equals)) return "YEARLY";
        return null;
    }
}
