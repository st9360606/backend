package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.ProviderErrorMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.*;

public class ProviderErrorMapperTest {

    @Test
    void retryAfter_seconds_should_parse() throws Exception {
        // 建議：把 parseRetryAfterSecondsOrNull 改成 package-private static，測試就不用反射
        var m = ProviderErrorMapper.class.getDeclaredMethod("parseRetryAfterSecondsOrNull", String.class);
        m.setAccessible(true);

        Integer v = (Integer) m.invoke(null, "30");
        assertThat(v).isEqualTo(30);

        Integer capped = (Integer) m.invoke(null, "999999");
        assertThat(capped).isEqualTo(3600);

        Integer bad = (Integer) m.invoke(null, "Mon, 01 Jan 2026 00:00:00 GMT");
        assertThat(bad).isNull();
    }
    @Test
    void socket_timeout_should_map_to_PROVIDER_TIMEOUT() {
        var m = ProviderErrorMapper.map(new SocketTimeoutException("x"));
        assertThat(m.code()).isEqualTo("PROVIDER_TIMEOUT");
    }

    @Test
    void rest_client_with_timeout_cause_should_map_to_PROVIDER_TIMEOUT() {
        var e = new RestClientException("io", new SocketTimeoutException("x"));
        var m = ProviderErrorMapper.map(e);
        assertThat(m.code()).isEqualTo("PROVIDER_TIMEOUT");
    }
}
