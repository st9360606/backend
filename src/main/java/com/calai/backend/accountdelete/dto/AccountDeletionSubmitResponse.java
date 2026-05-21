package com.calai.backend.accountdelete.dto;

import java.time.Instant;

public record AccountDeletionSubmitResponse(
        boolean ok,
        String status,
        Instant requestedAtUtc
) {
}
