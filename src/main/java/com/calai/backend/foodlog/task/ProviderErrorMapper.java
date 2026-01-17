package com.calai.backend.foodlog.task;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;

public final class ProviderErrorMapper {

    private ProviderErrorMapper() {}

    public record Mapped(String code, String message) {}

    public static Mapped map(Throwable e) {
        if (e == null) return new Mapped("PROVIDER_FAILED", null);

        // ✅ 你自己 throw 的明確 code（例如 PROVIDER_BAD_RESPONSE / GEMINI_API_KEY_MISSING）
        if (e instanceof IllegalStateException ise) {
            String m = ise.getMessage();
            if (m != null && (m.startsWith("PROVIDER_") || m.startsWith("GEMINI_") || m.equals("EMPTY_IMAGE"))) {
                return new Mapped(m, safeMsg(ise));
            }
        }

        // ✅ RestClient 4xx/5xx
        if (e instanceof RestClientResponseException re) {
            var sc = re.getStatusCode(); // HttpStatusCode
            int status = sc.value();

            // Gemini 常見 blocked：從 body 關鍵字判斷（不把 body 原樣回傳給 client）
            String body = nullSafe(re.getResponseBodyAsString());
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.contains("safety") || lower.contains("blocked") || lower.contains("recitation")) {
                return new Mapped("PROVIDER_BLOCKED", "blocked by provider policy");
            }

            if (status == 401 || status == 403) return new Mapped("PROVIDER_AUTH_FAILED", "auth failed");
            if (status == 429) return new Mapped("PROVIDER_RATE_LIMITED", "rate limited");
            if (status == 408) return new Mapped("PROVIDER_TIMEOUT", "timeout");

            if (sc.is5xxServerError()) return new Mapped("PROVIDER_UPSTREAM_5XX", "upstream 5xx");
            if (sc.is4xxClientError()) return new Mapped("PROVIDER_BAD_REQUEST", "bad request");

            return new Mapped("PROVIDER_FAILED", "http error");
        }

        // ✅ timeout / network
        if (e instanceof ResourceAccessException rae) {
            if (isTimeout(rae)) return new Mapped("PROVIDER_TIMEOUT", safeMsg(rae));
            return new Mapped("PROVIDER_NETWORK_ERROR", safeMsg(rae));
        }

        if (e instanceof RestClientException rce) {
            // 沒有 status code 的 client exception（例如序列化、未知連線錯）
            return new Mapped("PROVIDER_CLIENT_ERROR", safeMsg(rce));
        }

        // fallback
        return new Mapped("PROVIDER_FAILED", safeMsg(e));
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
