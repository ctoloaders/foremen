package com.foremen.service.procurement.position;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

/**
 * FOR-11-00 (R10): maps the caller to a {@link Audience}. ADMIN, MANAGER and BUYER are
 * {@code STAFF_FULL}; CLIENT is {@code CLIENT}; every other role (FOREMAN, WORKER, ...) is
 * {@code SITE}. No authentication means the most restrictive audience, {@code SITE}.
 */
@Component
public class PositionAudienceResolver {

    private final ProcurementProjectAccess access;

    public PositionAudienceResolver(ProcurementProjectAccess access) {
        this.access = access;
    }

    public Audience current() {
        return resolve(SecurityContextHolder.getContext().getAuthentication());
    }

    public Audience resolve(Authentication auth) {
        if (access.isMoneyPrivileged(auth)) {
            return Audience.STAFF_FULL;
        }
        if (hasAuthority(auth, "CLIENT")) {
            return Audience.CLIENT;
        }
        return Audience.SITE;
    }

    public boolean isStaffFull() {
        return current() == Audience.STAFF_FULL;
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
