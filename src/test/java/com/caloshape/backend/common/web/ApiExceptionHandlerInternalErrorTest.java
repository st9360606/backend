package com.caloshape.backend.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerInternalErrorTest {

    private static final String SENSITIVE_MESSAGE = "jdbc:mysql://db.internal/users?password=secret";
    private final ApiExceptionHandler advice = new ApiExceptionHandler();

    @Test
    void unknownExceptionReturnsSanitizedContractWithExistingRequestId() {
        MockHttpServletRequest request = request("RID-COMMON-500");

        var response = advice.handleUnknown(new RuntimeException(SENSITIVE_MESSAGE), request);

        assertSanitized(response.getStatusCode(), response.getBody(), "RID-COMMON-500");
    }

    @Test
    void unknownIllegalStateDoesNotUseExceptionMessageAsErrorCode() {
        MockHttpServletRequest request = request("RID-STATE-500");

        var response = advice.handleIllegalState(
                new IllegalStateException(SENSITIVE_MESSAGE),
                request
        );

        assertSanitized(response.getStatusCode(), response.getBody(), "RID-STATE-500");
    }

    @Test
    void serverResponseStatusReasonIsSanitized() {
        MockHttpServletRequest request = request("RID-RSE-500");

        var response = advice.handleResponseStatus(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SENSITIVE_MESSAGE),
                request
        );

        assertSanitized(response.getStatusCode(), response.getBody(), "RID-RSE-500");
    }

    private static MockHttpServletRequest request(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.setAttribute(RequestIdFilter.ATTR, requestId);
        return request;
    }

    private static void assertSanitized(
            HttpStatusCode actualStatus,
            java.util.Map<String, Object> body,
            String requestId
    ) {
        assertThat(actualStatus).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body)
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("message", "Unexpected error")
                .containsEntry("requestId", requestId)
                .doesNotContainValue(SENSITIVE_MESSAGE);
    }
}
