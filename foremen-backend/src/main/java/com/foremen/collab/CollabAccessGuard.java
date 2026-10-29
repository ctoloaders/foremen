package com.foremen.collab;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-05-13 — access parity for the collaboration channel. A STOMP subscribe/send on an estimate room
 * is allowed exactly when the equivalent REST estimate call would be: the caller's role holds the
 * {@code ESTIMATE} operation ({@code READ} to watch, {@code UPDATE} to stage edits) AND the project is
 * one of the caller's Accessible_Projects. ADMIN bypasses both (mirrors
 * {@link ForemenPermissionEvaluator} and the project-scope filter).
 */
@Component
public class CollabAccessGuard {

    static final String ESTIMATE_RESOURCE = "ESTIMATE";
    static final String READ = "READ";
    static final String UPDATE = "UPDATE";

    private final ForemenPermissionEvaluator permissionEvaluator;
    private final ProjectAccessCache projectAccessCache;

    public CollabAccessGuard(ForemenPermissionEvaluator permissionEvaluator,
                             ProjectAccessCache projectAccessCache) {
        this.permissionEvaluator = permissionEvaluator;
        this.projectAccessCache = projectAccessCache;
    }

    /**
     * Whether {@code user} may perform {@code operation} on the estimate of {@code projectId}.
     *
     * @param user      the authenticated STOMP principal ({@code null} → denied)
     * @param projectId the estimate's project id ({@code null} → denied)
     * @param operation {@link #READ} or {@link #UPDATE}
     */
    public boolean isAllowed(CollabPrincipal user, Long projectId, String operation) {
        if (user == null || projectId == null || user.roleCode() == null) {
            return false;
        }
        if (!permissionEvaluator.isAllowed(user.roleCode(), ESTIMATE_RESOURCE, operation)) {
            return false;
        }
        if (ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(user.roleCode())) {
            return true;
        }
        Set<Long> allowed = projectAccessCache.get(user.userId());
        return allowed != null && allowed.contains(projectId);
    }
}
