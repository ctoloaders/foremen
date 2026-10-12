package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Update payload for the generic user admin editor ({@code PUT /api/users/{id}}).
 *
 * <p>FOR-05-10b (R1.1) — {@code workCategoryIds} replaces the user's ordered default
 * User_Work_Categories when the resolved role is WORKER (D-worker-only); for a non-WORKER user it is
 * ignored and the user's list is cleared / kept empty (R1.4). A null/omitted list means "no
 * categories". Order is preserved; validation lives in {@code UserService}.
 */
public record UserUpdateRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        @NotNull Long roleId,
        Boolean active,
        String locale,
        Map<String, Object> displayPreferences,
        List<Long> workCategoryIds
) {}
