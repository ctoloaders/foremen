package com.foremen.service.model;

public record UserServiceModel(
        Long id,
        String name,
        String email,
        String phone,
        Long roleId,
        String roleName,
        boolean active,
        String locale
) {}
