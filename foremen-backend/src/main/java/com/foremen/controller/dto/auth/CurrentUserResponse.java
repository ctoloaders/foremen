package com.foremen.controller.dto.auth;

import java.util.Set;

public record CurrentUserResponse(
    Long id,
    String name,
    String email,
    String roleCode,
    Set<PermissionView> permissions
) {}
