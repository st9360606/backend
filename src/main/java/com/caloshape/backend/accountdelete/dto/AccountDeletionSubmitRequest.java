package com.caloshape.backend.accountdelete.dto;

public record AccountDeletionSubmitRequest(
        Boolean subscriptionWarningAcknowledged,
        Boolean userRequestedGooglePlayCancel
) {
    public boolean isSubscriptionWarningAcknowledged() {
        return Boolean.TRUE.equals(subscriptionWarningAcknowledged);
    }

    public boolean isUserRequestedGooglePlayCancel() {
        return Boolean.TRUE.equals(userRequestedGooglePlayCancel);
    }
}
