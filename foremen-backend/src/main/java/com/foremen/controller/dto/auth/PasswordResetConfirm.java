package com.foremen.controller.dto.auth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record PasswordResetConfirm(
    @NotBlank String token,
    // FOR-03-09: same 8..72 policy as invite set-password (bcrypt input limit is 72 bytes).
    @NotBlank @Size(min = 8, max = 72) String newPassword
) {}
