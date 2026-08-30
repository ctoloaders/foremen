package com.foremen.controller.dto.auth;

import java.util.Set;

public record PermissionView(
    String resource,
    Set<String> operations
) {}
