package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.ProviderErrorMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderErrorMapperTest {

    @Test
    void socket_timeout_should_map_to_PROVIDER_TIMEOUT() {
        var m = ProviderErrorMapper.map(new SocketTimeoutException("x"));
        assertThat(m.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(m.retryAfterSec()).isNull();
    }

    @Test
    void rest_client_with_timeout_cause_should_map_to_PROVIDER_TIMEOUT() {
        var e = new RestClientException("io", new SocketTimeoutException("x"));
        var m = ProviderErrorMapper.map(e);
        assertThat(m.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(m.retryAfterSec()).isNull();
    }

    @Test
    void rate_limited_should_parse_retry_after_header_seconds() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");

        RestClientResponseException ex = ex(429, headers, """
            {"error":{"code":429,"message":"quota exceeded"}}
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(30);
    }

    @Test
    void rate_limited_should_parse_retryDelay_field_seconds() {
        RestClientResponseException ex = ex(429, null, """
            {
              "error": {
                "code": 429,
                "details": [
                  { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "19s" }
                ]
              }
            }
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(19); // >= MIN_429_RETRY_SEC(2)
    }

    @Test
    void rate_limited_should_parse_message_retry_in_seconds_and_ceil() {
        RestClientResponseException ex = ex(429, null, """
            {"error":{"code":429,"message":"Please retry in 19.618449406s."}}
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(20); // ceil(19.61) -> 20
    }

    @Test
    void rate_limited_retry_in_millis_should_never_return_0_and_respect_min_2s() {
        RestClientResponseException ex = ex(429, null, """
            {"error":{"code":429,"message":"Please retry in 356.313387ms."}}
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(2); // ms -> 1s，但 429 最小 2s
    }

    @Test
    void rate_limited_retryDelay_0s_should_be_clamped_to_min_2s() {
        RestClientResponseException ex = ex(429, null, """
            {
              "error": {
                "code": 429,
                "details": [
                  { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "0s" }
                ]
              }
            }
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(2); // MIN_429_RETRY_SEC
    }

    @Test
    void rate_limited_should_cap_retry_after_to_3600s() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "999999");

        RestClientResponseException ex = ex(429, headers, """
            {"error":{"code":429,"message":"quota exceeded"}}
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(m.retryAfterSec()).isEqualTo(3600);
    }

    @Test
    void upstream_503_should_parse_retryDelay_if_present() {
        RestClientResponseException ex = ex(503, null, """
            {
              "error": {
                "code": 503,
                "message": "overloaded",
                "details": [
                  { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "15s" }
                ]
              }
            }
            """);

        var m = ProviderErrorMapper.map(ex);

        assertThat(m.code()).isEqualTo("PROVIDER_UPSTREAM_5XX");
        assertThat(m.retryAfterSec()).isEqualTo(15);
    }

    private static RestClientResponseException ex(int status, HttpHeaders headers, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new RestClientResponseException(
                "http " + status,
                status,
                "",
                headers,
                bytes,
                StandardCharsets.UTF_8
        );
    }
}
