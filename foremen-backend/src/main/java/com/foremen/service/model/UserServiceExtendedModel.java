package com.foremen.service.model;

import java.util.Map;

public record UserServiceExtendedModel(
        Long id,
        String name,
        String email,
        String phone,
        Long roleId,
        String roleName,
        boolean active,
        String locale,
        Map<String, Object> displayPreferences
) {}
