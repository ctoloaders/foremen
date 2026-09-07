package com.foremen.controller.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The {@code newClient} branch of the {@link ClientBlock} (FOR-04-13 Requirement 2.5): describes a
 * CLIENT user to create as part of the custom project-creation transaction, reusing the FOR-03-05
 * client-registration flow. Deliberately carries <strong>no</strong> role identifier — the client's
 * project role is fixed to {@code CLIENT} by the server, never by the caller.
 *
 * @param name   the client's display name (required)
 * @param email  the client's email address (required); a duplicate surfaces the existing
 *               user-creation uniqueness error (Requirement 3.9)
 * @param phone  optional phone number
 * @param locale optional locale for the invitation
 */
public record NewClientInput(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone,
        String locale
) {}
