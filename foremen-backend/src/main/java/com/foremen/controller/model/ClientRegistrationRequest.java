package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request payload for the dedicated client-registration path ({@code POST /api/users/client}).
 *
 * <p><b>FOR-05-09 (task 14.1) — optional {@code tags}.</b> The {@code tags} field is an additive
 * extension of the FOR-03-05 request: when present it is passed through to the CLIENT membership
 * assign and stored after {@code Tag_Normalization} (Requirement 15); when absent (null) the flow
 * behaves exactly as before this spec — the membership is created with an empty tag list and the
 * contract and OTP invitation email are otherwise unchanged (Requirement 12 criteria 1, 6).
 */
public record ClientRegistrationRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        String locale,
        @NotNull Long projectId,
        List<String> tags
) {}
