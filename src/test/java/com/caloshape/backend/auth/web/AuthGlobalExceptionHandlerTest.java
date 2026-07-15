package com.caloshape.backend.auth.web;

import com.caloshape.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthGlobalExceptionHandlerTest {

    @Test
    void generic500DoesNotExposeExceptionTypeOrMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/test");
        request.setAttribute(RequestIdFilter.ATTR, "RID-AUTH-GLOBAL");

        var response = new AuthGlobalExceptionHandler().handle(
                new IllegalStateException("smtp password=secret"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("message", "Unexpected error")
                .containsEntry("requestId", "RID-AUTH-GLOBAL")
                .doesNotContainKey("error")
                .doesNotContainValue("smtp password=secret");
    }
}
