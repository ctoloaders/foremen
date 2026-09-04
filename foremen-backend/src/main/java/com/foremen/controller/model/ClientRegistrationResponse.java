package com.foremen.controller.model;

public record ClientRegistrationResponse(
        Long id,
        String email,
        Long projectId
) {}
