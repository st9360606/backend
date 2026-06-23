package com.caloshape.backend.users.user.dto;

public record MeDto(
        Long id,
        String email,
        String name,
        String picture
) {}
