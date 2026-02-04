package com.calai.backend.foodlog.model;

/**
 * 對應規格 v1.2：
 * - SAFETY
 * - RECITATION
 * - HARM_CATEGORY（被 policy block 的類別）
 */
public enum ProviderRefuseReason {
    SAFETY,
    RECITATION,
    HARM_CATEGORY;

    public static ProviderRefuseReason fromErrorCodeOrNull(String code) {
        if (code == null) return null;
        if (!code.startsWith("PROVIDER_REFUSED_")) return null;
        String tail = code.substring("PROVIDER_REFUSED_".length());
        try { return ProviderRefuseReason.valueOf(tail); }
        catch (Exception ignore) { return null; }
    }
}