package com.calai.backend.auth.email;

public record EmailLoginCodeRequestedEvent(
        String toEmail,
        String code,
        int ttlMinutes
) {
}
