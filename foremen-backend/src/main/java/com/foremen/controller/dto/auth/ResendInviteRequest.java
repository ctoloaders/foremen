package com.foremen.controller.dto.auth;

import jakarta.validation.constraints.NotNull;

public record ResendInviteRequest(
    @NotNull Long userId
) {}
