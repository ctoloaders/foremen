package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record UserUpdateRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        @NotNull Long roleId,
        Boolean active,
        String locale,
        Map<String, Object> displayPreferences
) {}
