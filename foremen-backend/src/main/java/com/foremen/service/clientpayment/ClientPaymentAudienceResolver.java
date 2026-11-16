package com.foremen.service.clientpayment;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * FOR-11-06 (design 2): maps the caller to the DTO audience ({@code STAFF} or {@code CLIENT}) and to the
 * per-role {@link ClientPaymentVisibility}. No authentication means {@code NONE} (sees nothing).
 */
@Component
public class ClientPaymentAudienceResolver {

    public enum Audience {
        STAFF,
        CLIENT
    }

    public ClientPaymentVisibility visibility() {
        return visibility(SecurityContextHolder.getContext().getAuthentication());
    }

    public ClientPaymentVisibility visibility(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ClientPaymentVisibility.NONE;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("ROLE_")) {
                ClientPaymentVisibility v = ClientPaymentVisibility.forRole(a.substring("ROLE_".length()));
                if (v != ClientPaymentVisibility.NONE) {
                    return v;
                }
            }
        }
        return ClientPaymentVisibility.NONE;
    }

    public Audience audience(ClientPaymentVisibility visibility) {
        return visibility == ClientPaymentVisibility.CLIENT ? Audience.CLIENT : Audience.STAFF;
    }

    public Audience current() {
        return audience(visibility());
    }
}
