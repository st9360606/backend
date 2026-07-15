package com.caloshape.backend.auth.web;

import com.caloshape.backend.auth.service.EmailAuthRateLimitException;
import com.caloshape.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WebExceptionAdviceEmailAuthTest {

    private final WebExceptionAdvice advice = new WebExceptionAdvice();

    @Test
    void rateLimitResponseIs429AndIncludesRetryAfter() {
        var response = advice.handleRateLimited(new EmailAuthRateLimitException(321));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("321");
        assertThat(response.getBody()).containsEntry("code", "TOO_MANY_ATTEMPTS");
    }

    @Test
    void redisFailureResponseIsSanitized503() {
        var response = advice.handleAuthUnavailable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "AUTH_TEMPORARILY_UNAVAILABLE");
        assertThat(response.getBody()).doesNotContainValue("redis unavailable");
    }

    @Test
    void generic500IncludesRequestIdWithoutInternalDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/email/verify");
        request.setAttribute(RequestIdFilter.ATTR, "RID-AUTH-500");

        var response = advice.handleGeneric(
                new RuntimeException("redis://internal:6379 secret"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("message", "Unexpected error")
                .containsEntry("requestId", "RID-AUTH-500")
                .doesNotContainValue("redis://internal:6379 secret");
    }
}
