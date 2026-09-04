package com.foremen.controller.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/auth/google}: the Google ID token obtained by the
 * client via Google Identity Services, to be verified server-side against the configured
 * Google client (audience = {@code foremen.google.client-id}).
 */
public record GoogleLoginRequest(
    @NotBlank String idToken
) {}
