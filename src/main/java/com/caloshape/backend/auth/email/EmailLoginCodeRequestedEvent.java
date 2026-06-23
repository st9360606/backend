package com.caloshape.backend.auth.email;

public record EmailLoginCodeRequestedEvent(
        String toEmail,
        String code,
        int ttlMinutes
) {
}
