package com.calai.backend.foodlog.task;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

public final class ProviderErrorMapper {

    private ProviderErrorMapper() {}

    public record Mapped(String code, String message, Integer retryAfterSec) {}

    public static Mapped map(Throwable e) {
        if (e == null) return new Mapped("PROVIDER_FAILED", null, null);

        // ✅ NEW: 先攔截「任何 timeout」，包含你 test 直接丟 SocketTimeoutException 的情況
        if (isTimeoutThrowable(e)) {
            return new Mapped("PROVIDER_TIMEOUT", safeMsg(e), null);
        }

        // ✅ 你自己 throw 的明確 code
        if (e instanceof IllegalStateException ise) {
            String m = ise.getMessage();
            if (m != null && (m.startsWith("PROVIDER_") || m.startsWith("GEMINI_") || m.equals("EMPTY_IMAGE"))) {
                return new Mapped(m, safeMsg(ise), null);
            }
        }

        // ✅ RestClient 4xx/5xx
        if (e instanceof RestClientResponseException re) {
            var sc = re.getStatusCode();
            int status = sc.value();

            Integer retryAfter = null;
            HttpHeaders headers = re.getResponseHeaders();
            if (headers != null) {
                String ra = headers.getFirst("Retry-After");
                retryAfter = parseRetryAfterSecondsOrNull(ra);
            }

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

        // ✅ network (非 timeout)
        if (e instanceof ResourceAccessException rae) {
            // timeout 已在 isTimeoutThrowable 擋掉，這裡就是純 network error
            return new Mapped("PROVIDER_NETWORK_ERROR", safeMsg(rae), null);
        }

        if (e instanceof RestClientException rce) {
            // ✅ 沒有 status code 的 client exception（序列化、連線錯）
            // timeout 已在 isTimeoutThrowable 擋掉
            return new Mapped("PROVIDER_CLIENT_ERROR", safeMsg(rce), null);
        }

        return new Mapped("PROVIDER_FAILED", safeMsg(e), null);
    }

    private static Integer parseRetryAfterSecondsOrNull(String ra) {
        if (ra == null || ra.isBlank()) return null;
        try {
            int v = Integer.parseInt(ra.trim());
            v = Math.max(0, Math.min(v, 3600)); // 上限 1 小時
            return v;
        } catch (Exception ignored) {
            return null; // HTTP-date 格式 MVP 先不處理
        }
    }

    /**
     * ✅ NEW: 判斷「任何 Throwable」是不是 timeout（含 cause chain）
     * - 你 test 直接丟 SocketTimeoutException
     * - Spring RestClient 常把 timeout 包在 ResourceAccessException / RestClientException cause 裡
     * - JDK HttpClient 可能是 java.net.http.HttpTimeoutException（用 class name 判斷避免編譯依賴）
     */
    private static boolean isTimeoutThrowable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException) return true;
            if (c instanceof TimeoutException) return true;

            // 避免直接引用 java.net.http.HttpTimeoutException（有些 JDK/環境不一定）
            String cn = c.getClass().getName();
            if ("java.net.http.HttpTimeoutException".equals(cn)) return true;

            // 有些 library 只在 message 放 timed out
            String m = c.getMessage();
            if (m != null) {
                String s = m.toLowerCase(Locale.ROOT);
                if (s.contains("timeout") || s.contains("timed out")) return true;
            }
        }
        return false;
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
