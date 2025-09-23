package com.calai.backend.auth.dto;

public record VerifyRequest(String email, String code) {}
