package com.caloshape.backend.entitlement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EntitlementSyncRequest(
        @NotNull
        @Size(max = 20)
        List<@NotNull @Valid PurchaseTokenPayload> purchases
) {
    public record PurchaseTokenPayload(
            @NotBlank
            @Size(max = 200)
            String productId,

            @NotBlank
            @Size(max = 8192)
            String purchaseToken
    ) {}
}
