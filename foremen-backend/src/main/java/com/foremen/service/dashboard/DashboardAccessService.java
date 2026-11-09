package com.foremen.service.dashboard;

import com.foremen.dao.ProjectDao;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.permission.PermissionCache;
import com.foremen.service.permission.PermissionSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dashboard widget access evaluation. Has_Access means the role holds ANY operation on EACH
 * required resource. Uses the same {@link PermissionCache} / {@link ForemenPermissionEvaluator}
 * as the permission interceptor, so matrix changes take effect with the existing invalidation.
 */
@Service
@RequiredArgsConstructor
public class DashboardAccessService {

    private final ForemenPermissionEvaluator evaluator;
    private final PermissionCache permissionCache;
    private final ProjectAccessCache projectAccessCache;
    private final ProjectDao projectDao;

    /** Required resources on which the role holds no operation at all. ADMIN returns an empty set. */
    @Transactional(readOnly = true)
    public Set<String> missingResources(String roleCode, Set<String> required) {
        if (required == null || required.isEmpty()
                || ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(roleCode)) {
            return Set.of();
        }
        PermissionSet set = permissionCache.get(roleCode, evaluator::loadPermissionSet);
        Set<String> missing = new LinkedHashSet<>();
        for (String resource : required) {
            if (!set.hasAnyOperation(resource)) {
                missing.add(resource);
            }
        }
        return missing;
    }

    /**
     * Order: inactive -> INACTIVE; missing resources -> NO_ACCESS; project widget whose bound project
     * is absent / not accessible -> PROJECT_UNAVAILABLE; else AVAILABLE.
     *
     * @param projectWidget true for a SINGLE_PROJECT widget
     */
    @Transactional(readOnly = true)
    public WidgetState evaluate(Long userId, String roleCode, boolean widgetActive,
                                Set<String> requiredResources, boolean projectWidget,
                                Long boundProjectId) {
        if (!widgetActive) {
            return WidgetState.INACTIVE;
        }
        if (!missingResources(roleCode, requiredResources).isEmpty()) {
            return WidgetState.NO_ACCESS;
        }
        if (projectWidget) {
            if (boundProjectId == null) {
                return WidgetState.PROJECT_UNAVAILABLE;
            }
            boolean admin = ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(roleCode);
            boolean ok = admin
                    ? projectDao.existsById(boundProjectId)
                    : userId != null && projectAccessCache.get(userId).contains(boundProjectId);
            if (!ok) {
                return WidgetState.PROJECT_UNAVAILABLE;
            }
        }
        return WidgetState.AVAILABLE;
    }
}
