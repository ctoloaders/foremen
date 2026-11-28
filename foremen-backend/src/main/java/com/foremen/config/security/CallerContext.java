package com.foremen.config.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Read-only access to the authenticated caller's id and company role code, resolved from the JWT
 * authentication the security filter installs (subject = user id, authority {@code ROLE_<CODE>}).
 *
 * <p>Used by services that narrow or mask data for a specific role, for example the WORKER role
 * (FOR-07-06 / FOR-04-19 bug reports, 2026-11-27): payroll reads limited to the caller's own rows, and
 * work prices cleared from the DTOs.
 */
public final class CallerContext {

    /** The company role code of a worker. */
    public static final String WORKER_ROLE_CODE = "WORKER";

    private CallerContext() {
    }

    /** The caller's user id, or {@code null} when unauthenticated or the subject is not numeric. */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The caller's company role code (without the {@code ROLE_} prefix), or {@code null}. */
    public static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }

    /** True iff the caller's company role is exactly {@code WORKER}. */
    public static boolean isWorker() {
        return WORKER_ROLE_CODE.equals(currentRoleCode());
    }
}
