package com.foremen.controller.model;

import java.util.List;
import java.util.Map;

/**
 * Single-user detail DTO returned by {@code GET /api/users/{id}} (the user admin edit view).
 *
 * <p>FOR-05-10b (R1.5) — {@code workCategories} carries the user's ordered default
 * User_Work_Categories (id + localized name, ru → pl → code fallback). It is populated for WORKER
 * users only; a non-WORKER user carries an empty list (D-worker-only).
 */
public record UserDtoExtendedModel(
        Long id,
        String name,
        String email,
        String phone,
        Long roleId,
        String roleName,
        boolean active,
        String locale,
        Map<String, Object> displayPreferences,
        List<RefDto> workCategories
) {}
