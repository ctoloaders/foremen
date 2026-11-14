package com.foremen.service.procurement.access;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.service.ProjectAccessCache;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * FOR-11-00 project access for the procurement services. A BUYER sees every project; every other
 * role sees its ACTIVE memberships, resolved by the same {@link ProjectAccessCache} the other
 * {@code ProjectScopedService} implementations use. The ADMIN bypass stays in
 * {@code ProjectScopedService.addRequiredQuery}.
 */
@Component
public class ProcurementProjectAccess {

    public static final String BUYER_ROLE_CODE = "BUYER";

    private final ProjectAccessCache projectAccessCache;
    private final UserDao userDao;
    private final ProjectDao projectDao;

    public ProcurementProjectAccess(ProjectAccessCache projectAccessCache, UserDao userDao,
                                    ProjectDao projectDao) {
        this.projectAccessCache = projectAccessCache;
        this.userDao = userDao;
        this.projectDao = projectDao;
    }

    /** BUYER: all project ids; others: the user's ACTIVE memberships. Never null. */
    public Set<Long> allowedProjectIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        if (userDao.findRoleCodeById(userId).filter(BUYER_ROLE_CODE::equals).isPresent()) {
            return new HashSet<>(projectDao.findAllIds());
        }
        Set<Long> ids = projectAccessCache.get(userId);
        return ids == null ? Set.of() : ids;
    }

    /** True when the authentication carries {@code ROLE_BUYER}. */
    public boolean isBuyer(Authentication a) {
        return hasAuthority(a, "BUYER");
    }

    /** True for ADMIN, MANAGER or BUYER: callers allowed to see money fields. */
    public boolean isMoneyPrivileged(Authentication a) {
        return hasAuthority(a, "ADMIN") || hasAuthority(a, "MANAGER") || hasAuthority(a, "BUYER");
    }

    private static boolean hasAuthority(Authentication a, String code) {
        if (a == null || !a.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : a.getAuthorities()) {
            if (("ROLE_" + code).equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
