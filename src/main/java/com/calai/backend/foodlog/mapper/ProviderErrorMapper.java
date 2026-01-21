package com.calai.backend.foodlog.mapper;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderErrorMapper {

    private ProviderErrorMapper() {}

    public record Mapped(String code, String message, Integer retryAfterSec) {}

    // "retryDelay": "59s"
    private static final Pattern RETRY_DELAY_FIELD = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"", Pattern.CASE_INSENSITIVE);

    // "Please retry in 19.618449406s."
    private static final Pattern RETRY_IN_SECONDS = Pattern.compile("retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

    // "Please retry in 356.313387ms."
    private static final Pattern RETRY_IN_MILLIS = Pattern.compile("retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)ms", Pattern.CASE_INSENSITIVE);

    // 429 最小等待，避免 retryDelay=0s 或 356ms 被 round 到 0 造成抖動
    private static final int MIN_429_RETRY_SEC = 2;

    // 上限 1 小時
    private static final int MAX_RETRY_SEC = 3600;

    public static Mapped map(Throwable e) {
        if (e == null) return new Mapped("PROVIDER_FAILED", null, null);

        // ✅ timeout：用型別判斷為主，避免誤判
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
            int status = re.getStatusCode().value();

            HttpHeaders headers = re.getResponseHeaders();
            String body = nullSafe(re.getResponseBodyAsString());

            Integer retryAfter = parseRetryAfterHeaderSecondsOrNull(headers);
            if (retryAfter == null) retryAfter = parseRetryAfterFromBodyOrNull(body);

            // blocked
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.contains("safety") || lower.contains("blocked") || lower.contains("recitation")) {
                return new Mapped("PROVIDER_BLOCKED", "blocked by provider policy", null);
            }

            if (status == 401 || status == 403) return new Mapped("PROVIDER_AUTH_FAILED", "auth failed", null);

            if (status == 429) {
                // ✅ 429 一律給最小等待（避免抖動）
                int sec = retryAfter == null ? MIN_429_RETRY_SEC : Math.max(MIN_429_RETRY_SEC, retryAfter);
                return new Mapped("PROVIDER_RATE_LIMITED", "rate limited", clampSec(sec));
            }

            if (status == 408) {
                return new Mapped("PROVIDER_TIMEOUT", "timeout", retryAfter);
            }

            if (re.getStatusCode().is5xxServerError()) {
                // 503 overloaded 常見，也可能帶 retryDelay
                if (retryAfter != null) retryAfter = clampSec(retryAfter);
                return new Mapped("PROVIDER_UPSTREAM_5XX", "upstream 5xx", retryAfter);
            }

            if (re.getStatusCode().is4xxClientError()) {
                return new Mapped("PROVIDER_BAD_REQUEST", "bad request", null);
            }

            return new Mapped("PROVIDER_FAILED", "http error", retryAfter);
        }

        // ✅ network (非 timeout)
        if (e instanceof ResourceAccessException rae) {
            return new Mapped("PROVIDER_NETWORK_ERROR", safeMsg(rae), null);
        }

        if (e instanceof RestClientException rce) {
            return new Mapped("PROVIDER_CLIENT_ERROR", safeMsg(rce), null);
        }

        return new Mapped("PROVIDER_FAILED", safeMsg(e), null);
    }

    private static Integer parseRetryAfterHeaderSecondsOrNull(HttpHeaders headers) {
        if (headers == null) return null;
        String ra = headers.getFirst("Retry-After");
        if (ra == null || ra.isBlank()) return null;
        try {
            int v = Integer.parseInt(ra.trim());
            return clampSec(v);
        } catch (Exception ignored) {
            return null; // MVP 不處理 HTTP-date
        }
    }

    private static Integer parseRetryAfterFromBodyOrNull(String body) {
        if (body == null || body.isBlank()) return null;

        String lower = body.toLowerCase(Locale.ROOT);

        // 1) retryDelay: "59s"
        Matcher m1 = RETRY_DELAY_FIELD.matcher(lower);
        if (m1.find()) {
            try {
                int sec = Integer.parseInt(m1.group(1));
                return clampSec(sec);
            } catch (Exception ignored) {}
        }

        // 2) Please retry in 19.61s
        Matcher m2 = RETRY_IN_SECONDS.matcher(lower);
        if (m2.find()) {
            try {
                double sec = Double.parseDouble(m2.group(1));
                int v = (int) Math.ceil(sec);
                return clampSec(v);
            } catch (Exception ignored) {}
        }

        // 3) Please retry in 356ms
        Matcher m3 = RETRY_IN_MILLIS.matcher(lower);
        if (m3.find()) {
            try {
                double ms = Double.parseDouble(m3.group(1));
                int v = (int) Math.ceil(ms / 1000.0);
                // ms 最少也回 1 秒，避免 0
                v = Math.max(1, v);
                return clampSec(v);
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static int clampSec(int v) {
        if (v < 0) v = 0;
        if (v > MAX_RETRY_SEC) v = MAX_RETRY_SEC;
        return v;
    }

    private static boolean isTimeoutThrowable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException) return true;
            if (c instanceof TimeoutException) return true;
            String cn = c.getClass().getName();
            if ("java.net.http.HttpTimeoutException".equals(cn)) return true;
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
