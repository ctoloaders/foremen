package com.foremen.controller.model;

import java.util.Map;

public record UserCreateResponse(
        Long id,
        String name,
        String email,
        String phone,
        Long roleId,
        boolean active,
        String locale,
        Map<String, Object> displayPreferences
) {}
