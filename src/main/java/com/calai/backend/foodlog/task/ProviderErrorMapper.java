package com.calai.backend.foodlog.task;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;

public final class ProviderErrorMapper {

    private ProviderErrorMapper() {}

    /**
     * ✅ 新增 retryAfterSec：
     * - 429 時從 Retry-After 讀秒數（若沒有就 null）
     * - worker 會用 max(delay, retryAfterSec) 決定下次重試時間
     */
    public record Mapped(String code, String message, Integer retryAfterSec) {}

    public static Mapped map(Throwable e) {
        if (e == null) return new Mapped("PROVIDER_FAILED", null, null);

        // ✅ 你自己 throw 的明確 code（例如 PROVIDER_BAD_RESPONSE / PROVIDER_NOT_AVAILABLE / GEMINI_API_KEY_MISSING）
        if (e instanceof IllegalStateException ise) {
            String m = ise.getMessage();
            if (m != null && (m.startsWith("PROVIDER_") || m.startsWith("GEMINI_") || m.equals("EMPTY_IMAGE"))) {
                return new Mapped(m, safeMsg(ise), null);
            }
        }

        // ✅ RestClient 4xx/5xx（Gemini / LogMeal 都走這裡）
        if (e instanceof RestClientResponseException re) {
            var sc = re.getStatusCode();
            int status = sc.value();

            // 讀 Retry-After（只處理秒數格式）
            Integer retryAfter = null;
            HttpHeaders headers = re.getResponseHeaders();
            if (headers != null) {
                String ra = headers.getFirst("Retry-After");
                retryAfter = parseRetryAfterSecondsOrNull(ra);
            }

            // Gemini 常見 blocked：從 body 關鍵字判斷（不要把 body 原樣回傳給 client）
            String body = nullSafe(re.getResponseBodyAsString());
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.contains("safety") || lower.contains("blocked") || lower.contains("recitation")) {
                return new Mapped("PROVIDER_BLOCKED", "blocked by provider policy", null);
            }

            if (status == 401 || status == 403) return new Mapped("PROVIDER_AUTH_FAILED", "auth failed", null);
            if (status == 429) return new Mapped("PROVIDER_RATE_LIMITED", "rate limited", retryAfter);
            if (status == 408) return new Mapped("PROVIDER_TIMEOUT", "timeout", retryAfter);

            if (sc.is5xxServerError()) return new Mapped("PROVIDER_UPSTREAM_5XX", "upstream 5xx", retryAfter);
            if (sc.is4xxClientError()) return new Mapped("PROVIDER_BAD_REQUEST", "bad request", null);

            return new Mapped("PROVIDER_FAILED", "http error", retryAfter);
        }

        // ✅ timeout / network
        if (e instanceof ResourceAccessException rae) {
            if (isTimeout(rae)) return new Mapped("PROVIDER_TIMEOUT", safeMsg(rae), null);
            return new Mapped("PROVIDER_NETWORK_ERROR", safeMsg(rae), null);
        }

        if (e instanceof RestClientException rce) {
            // 沒有 status code 的 client exception（例如序列化、未知連線錯）
            return new Mapped("PROVIDER_CLIENT_ERROR", safeMsg(rce), null);
        }

        // fallback
        return new Mapped("PROVIDER_FAILED", safeMsg(e), null);
    }

    private static Integer parseRetryAfterSecondsOrNull(String ra) {
        if (ra == null || ra.isBlank()) return null;
        try {
            int v = Integer.parseInt(ra.trim());
            // ✅ MVP：上限 1 小時
            v = Math.max(0, Math.min(v, 3600));
            return v;
        } catch (Exception ignored) {
            // HTTP-date 格式先不處理（MVP）
            return null;
        }
    }

    private static boolean isTimeout(ResourceAccessException e) {
        Throwable c = e.getCause();
        if (c instanceof SocketTimeoutException) return true;

        String m = e.getMessage();
        if (m == null) return false;
        String s = m.toLowerCase(Locale.ROOT);
        return s.contains("timeout") || s.contains("timed out");
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
