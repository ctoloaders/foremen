package com.foremen.service.permission;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Loads a role's {@link PermissionSet} (via {@link PermissionCache}) and decides allow/deny for a
 * given (resource, operation) pair, applying the ADMIN bypass.
 *
 * <p>Decision rules:
 * <ul>
 *   <li>A role code equal to the literal {@code ADMIN} short-circuits to allow before any matrix
 *       lookup (exact match, Requirements 3.1-3.3).</li>
 *   <li>Any other role resolves its {@link PermissionSet} through the cache and the decision equals
 *       exact membership of {@code "RESOURCE:OPERATION"} in that set — deny by default when the
 *       resource is absent, present without the operation, the set is empty, or the role is unknown
 *       (Requirements 1.1-1.4, 2.1-2.3).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ForemenPermissionEvaluator {

    public static final String ADMIN_ROLE_CODE = "ADMIN";

    private final RoleDao roleDao;
    private final PermissionCache cache;

    /**
     * Returns {@code true} iff the given role holds the (resource, operation) permission.
     * ADMIN is always allowed; every other decision is exact matrix membership, deny by default.
     */
    public boolean isAllowed(String roleCode, String resource, String operation) {
        if (ADMIN_ROLE_CODE.equals(roleCode)) {
            return true; // ADMIN bypass — exact code match, no matrix lookup
        }
        PermissionSet set = cache.get(roleCode, this::loadPermissionSet);
        return set.allows(resource, operation);
    }

    /**
     * Loads a role's {@link PermissionSet} from the database by role code. An unknown role resolves
     * to {@link PermissionSet#empty()} (deny by default). Otherwise each of the role's
     * {@link RoleResourceEntity} rows is flattened: the resource code is paired with each of its
     * operation codes into grant keys.
     */
    @Transactional(readOnly = true)
    public PermissionSet loadPermissionSet(String roleCode) {
        RoleEntity role = roleDao.findByCode(roleCode).orElse(null);
        if (role == null) {
            return PermissionSet.empty(); // unknown role -> deny
        }
        Set<String> grants = new HashSet<>();
        for (RoleResourceEntity rr : role.getRoleResources()) {
            String resourceCode = rr.getResource().getCode();
            rr.getOperations().forEach(op ->
                    grants.add(PermissionSet.key(resourceCode, op.getCode())));
        }
        return new PermissionSet(grants);
    }
}
