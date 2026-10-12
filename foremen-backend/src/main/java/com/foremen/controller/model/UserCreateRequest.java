package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Create payload for the generic user admin editor ({@code POST /api/users}).
 *
 * <p>FOR-05-10b (R1.1) — {@code workCategoryIds} carries the ordered, distinct default list of
 * Work_Categories a WORKER user can perform (User_Work_Categories). The list is applied only when
 * the resolved role is WORKER (D-worker-only); for a non-WORKER user it is ignored and the user's
 * list stays empty (R1.4). A null/omitted list means "no categories". Order is preserved; validation
 * (duplicate -> {@code error.user.workCategories.duplicate}, unknown/inactive ->
 * {@code error.user.workCategories.invalid}) lives in {@code UserService}.
 */
public record UserCreateRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        @NotNull Long roleId,
        String locale,
        Map<String, Object> displayPreferences,
        List<Long> workCategoryIds
) {}
