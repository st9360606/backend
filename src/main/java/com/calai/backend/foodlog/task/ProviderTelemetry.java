package com.calai.backend.foodlog.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProviderTelemetry {

    // ✅ 新增：含 modelId 的 OK log
    public void ok(String provider, String modelId, String foodLogId, long latencyMs,
                   Integer promptTok, Integer candTok, Integer totalTok) {
        log.info("provider_call status=OK provider={} modelId={} foodLogId={} latencyMs={} tokensPrompt={} tokensCand={} tokensTotal={}",
                safe(provider), safe(modelId), safe(foodLogId), latencyMs,
                n(promptTok), n(candTok), n(totalTok));
    }

    // ✅ 保留：舊方法相容（不改呼叫點也能編譯）
    public void ok(String provider, String foodLogId, long latencyMs,
                   Integer promptTok, Integer candTok, Integer totalTok) {
        ok(provider, null, foodLogId, latencyMs, promptTok, candTok, totalTok);
    }

    // ✅ 新增：含 modelId 的 FAIL log
    public void fail(String provider, String modelId, String foodLogId, long latencyMs,
                     String errorCode, Integer retryAfterSec) {
        log.warn("provider_call status=FAIL provider={} modelId={} foodLogId={} latencyMs={} errorCode={} retryAfterSec={}",
                safe(provider), safe(modelId), safe(foodLogId), latencyMs,
                safe(errorCode), n(retryAfterSec));
    }

    // ✅ 保留：舊方法相容
    public void fail(String provider, String foodLogId, long latencyMs,
                     String errorCode, Integer retryAfterSec) {
        fail(provider, null, foodLogId, latencyMs, errorCode, retryAfterSec);
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? "UNKNOWN" : s; }
    private static Object n(Integer v) { return v == null ? "NA" : v; }
}