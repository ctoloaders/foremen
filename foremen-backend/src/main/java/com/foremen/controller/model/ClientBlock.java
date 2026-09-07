package com.foremen.controller.model;

import jakarta.validation.Valid;

/**
 * The optional {@code client} section of a {@link CreateProjectRequest} (FOR-04-13 Requirement 2.5):
 * either references an existing CLIENT user by id ({@code existingClientUserId}) OR describes a new
 * client to create ({@code newClient}). Exactly one branch is expected; the client's project role is
 * always the server-resolved {@code CLIENT} role and is never supplied by the caller (no role
 * identifier is accepted here).
 *
 * @param existingClientUserId id of an existing CLIENT user to assign to the project, or {@code null}
 * @param newClient            a client to create and assign, or {@code null}
 */
public record ClientBlock(
        Long existingClientUserId,
        @Valid NewClientInput newClient
) {}
