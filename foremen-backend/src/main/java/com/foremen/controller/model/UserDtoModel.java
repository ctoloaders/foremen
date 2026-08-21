package com.foremen.controller.model;

public record UserDtoModel(
        Long id,
        String name,
        String email,
        boolean active,
        String roleName
) {}
