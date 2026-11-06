package com.foremen.service.roomsimport;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;

/**
 * FOR-05-02 (Requirement 14.2) — project-scope check for the rooms-import endpoints, the same approach
 * as {@code RoomsSaveService.assertProjectAccess}: ADMIN bypasses; everyone else needs the project in
 * their {@link ProjectAccessCache} set. A project / job outside the caller's access reads as
 * {@code 404 error.entity.not.found} so existence never leaks.
 */
@Component
public class RoomsImportAccessGuard {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final ProjectAccessCache projectAccessCache;

    public RoomsImportAccessGuard(ProjectAccessCache projectAccessCache) {
        this.projectAccessCache = projectAccessCache;
    }

    /** @throws ForemenApiException 404 when the caller may not access the project */
    public void assertProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw notFound(projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ADMIN_AUTHORITY.equals(ga.getAuthority())) {
                return;
            }
        }
        Long userId = currentUserId();
        Set<Long> allowed = userId == null ? Set.of() : projectAccessCache.get(userId);
        if (projectId == null || allowed == null || !allowed.contains(projectId)) {
            throw notFound(projectId);
        }
    }

    /** The authenticated user's numeric id (the principal name), or {@code null}. */
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

    public static ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, RoomsImportErrors.NOT_FOUND, id);
    }
}
