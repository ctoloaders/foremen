package com.foremen.collab;

import java.security.Principal;

/**
 * FOR-05-13 — the authenticated identity of a STOMP session, established once on {@code CONNECT}
 * from the same JWT the REST API uses (see {@link StompAuthChannelInterceptor}).
 *
 * <p>{@link #getName()} returns the numeric user id as a string, matching the REST principal name
 * ({@code JwtAuthenticationFilter} uses the {@code sub} claim), so Spring's user-destination support
 * and any {@code Principal}-based code see a consistent identity.
 *
 * @param userId   the user id ({@code sub} claim)
 * @param roleCode the user's role code (e.g. {@code MANAGER}, {@code ADMIN})
 * @param name     the user's display name (resolved once at connect)
 */
public record CollabPrincipal(Long userId, String roleCode, String name) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
