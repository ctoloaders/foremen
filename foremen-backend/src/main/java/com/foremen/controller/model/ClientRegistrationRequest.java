package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ClientRegistrationRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        String locale,
        @NotNull Long projectId
) {}
