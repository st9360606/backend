package com.caloshape.backend.entitlement.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementSyncRequestValidationTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsEmptyPurchaseListForPassiveServerSummaryRefresh() {
        var request = new EntitlementSyncRequest(List.of());

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsNullPurchaseList() {
        var request = new EntitlementSyncRequest(null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("purchases");
    }

    @Test
    void rejectsMoreThanTwentyPurchases() {
        var purchase = new EntitlementSyncRequest.PurchaseTokenPayload("monthly001", "token");
        var request = new EntitlementSyncRequest(Collections.nCopies(21, purchase));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("purchases");
    }

    @Test
    void rejectsNullBlankAndOversizedPurchaseFields() {
        var request = new EntitlementSyncRequest(List.of(
                null,
                new EntitlementSyncRequest.PurchaseTokenPayload(" ", " "),
                new EntitlementSyncRequest.PurchaseTokenPayload("p".repeat(201), "t".repeat(8193))
        ));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .anyMatch(path -> path.equals("purchases[0].<list element>"))
                .anyMatch(path -> path.contains("productId"))
                .anyMatch(path -> path.contains("purchaseToken"));
    }
}
