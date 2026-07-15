package com.caloshape.backend.entitlement.web;

import com.caloshape.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementExceptionAdviceTest {

    private final EntitlementExceptionAdvice advice = new EntitlementExceptionAdvice();

    @Test
    void purchaseTokenAlreadyBoundReturnsConflictContract() {
        var response = advice.handleIllegalState(
                new IllegalStateException(EntitlementExceptionAdvice.PURCHASE_TOKEN_ALREADY_BOUND),
                request("RID-CONFLICT")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry(
                "code",
                EntitlementExceptionAdvice.PURCHASE_TOKEN_ALREADY_BOUND
        );
        assertThat(response.getBody()).containsEntry(
                "message",
                EntitlementExceptionAdvice.PURCHASE_TOKEN_ALREADY_BOUND
        );
    }

    @Test
    void unrelatedIllegalStateRemainsInternalServerError() {
        var response = advice.handleIllegalState(
                new IllegalStateException("purchase token=secret"),
                request("RID-ENTITLEMENT-500")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("message", "Unexpected error")
                .containsEntry("requestId", "RID-ENTITLEMENT-500")
                .doesNotContainValue("purchase token=secret");
    }

    private static MockHttpServletRequest request(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/entitlements/sync");
        request.setAttribute(RequestIdFilter.ATTR, requestId);
        return request;
    }
}
