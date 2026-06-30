package com.caloshape.backend.entitlement.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementExceptionAdviceTest {

    private final EntitlementExceptionAdvice advice = new EntitlementExceptionAdvice();

    @Test
    void purchaseTokenAlreadyBoundReturnsConflictContract() {
        var response = advice.handleIllegalState(
                new IllegalStateException(EntitlementExceptionAdvice.PURCHASE_TOKEN_ALREADY_BOUND)
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
        var response = advice.handleIllegalState(new IllegalStateException("OTHER_ERROR"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("code", "OTHER_ERROR");
    }
}
