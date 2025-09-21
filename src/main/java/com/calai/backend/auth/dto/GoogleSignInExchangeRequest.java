// src/main/java/com/calai/app/auth/dto/GoogleSignInExchangeRequest.java
package com.calai.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleSignInExchangeRequest(
        @NotBlank String idToken,
        String clientId // 來自 App 的值不信任，可忽略
) {}
