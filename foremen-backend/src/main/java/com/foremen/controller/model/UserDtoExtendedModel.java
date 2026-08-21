package com.foremen.controller.model;

import java.util.Map;

public record UserDtoExtendedModel(
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
