package com.calai.backend.foodlog.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProviderTelemetry {

    public void ok(String provider, String foodLogId, long latencyMs,
                   Integer promptTok, Integer candTok, Integer totalTok) {
        log.info("provider_call status=OK provider={} foodLogId={} latencyMs={} tokensPrompt={} tokensCand={} tokensTotal={}",
                provider, foodLogId, latencyMs,
                n(promptTok), n(candTok), n(totalTok));
    }

    public void fail(String provider, String foodLogId, long latencyMs,
                     String errorCode, Integer retryAfterSec) {
        log.warn("provider_call status=FAIL provider={} foodLogId={} latencyMs={} errorCode={} retryAfterSec={}",
                provider, foodLogId, latencyMs,
                safe(errorCode), n(retryAfterSec));
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? "UNKNOWN" : s; }
    private static Object n(Integer v) { return v == null ? "NA" : v; }
}
