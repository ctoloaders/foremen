package com.foremen.controller.dto.auth;

public record TokenResponse(
    String accessToken,
    String refreshToken,
    long expiresIn
) {}
