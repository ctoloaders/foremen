package com.foremen.config.security;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} on matched controller handler methods before the controller
 * method executes.
 *
 * <p>In {@code preHandle}:
 * <ul>
 *   <li>Non-{@link HandlerMethod} handlers (static resources etc.) are skipped (Requirement 5.3).</li>
 *   <li>The method-level {@link RequiresPermission} is resolved via
 *       {@link HandlerMethod#getMethodAnnotation} — method-only, no type-level fallback
 *       (Requirements 4.3, 4.4). An unannotated handler proceeds without evaluation.</li>
 *   <li>The role code is read from the {@code ROLE_<code>} authority in the
 *       {@link org.springframework.security.core.context.SecurityContext} (Requirement 5.4).</li>
 *   <li>No authenticated principal on an annotated endpoint yields a defensive 401
 *       {@code error.auth.unauthorized} (Requirement 7.2).</li>
 *   <li>A deny decision from the {@link ForemenPermissionEvaluator} yields 403
 *       {@code error.access.denied} (Requirements 5.1, 5.2, 6.1); otherwise the request proceeds.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String ROLE_PREFIX = "ROLE_";

    private final ForemenPermissionEvaluator evaluator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // static resources etc. — no annotation to enforce
        }
        RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            return true; // unannotated endpoint -> no check (method-only, no type fallback)
        }
        String roleCode = currentRoleCode();
        if (roleCode == null) {
            // Defensive 401: annotated endpoint reached without an authenticated principal
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        if (!evaluator.isAllowed(roleCode, required.resource(), required.operation())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        return true;
    }

    private String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }
}
