package com.calai.backend.users.dto;

public record MeDto(
        Long id,
        String email,
        String name,
        String picture
) {}