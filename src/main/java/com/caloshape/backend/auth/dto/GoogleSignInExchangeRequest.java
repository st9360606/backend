// src/main/java/com/caloshape/app/auth/dto/GoogleSignInExchangeRequest.java
package com.caloshape.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleSignInExchangeRequest(
        @NotBlank String idToken,
        String clientId // 來自 App 的值不信任，可忽略
) {}
