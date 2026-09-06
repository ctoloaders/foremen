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
 * Enforces the ABAC permission matrix on matched controller handler methods before the controller
 * method executes.
 *
 * <p>In {@code preHandle}:
 * <ul>
 *   <li>Non-{@link HandlerMethod} handlers (static resources etc.) are skipped (Requirement 5.2).</li>
 *   <li>The {@code (resource, operation)} pair is derived by delegating to the
 *       {@link PermissionResolver}, which applies the {@link RequiresPermission} precedence and the
 *       {@link PermissionResource}/{@link PermissionOperation} combination. A {@code null} pair
 *       means the handler is Unguarded and the request proceeds without a matrix check
 *       (Requirement 5.1).</li>
 *   <li>The role code is read from the {@code ROLE_<code>} authority in the
 *       {@link org.springframework.security.core.context.SecurityContext}.</li>
 *   <li>A resolved pair with no authenticated principal yields a defensive 401
 *       {@code error.auth.unauthorized} (Requirement 7.1).</li>
 *   <li>A deny decision from the {@link ForemenPermissionEvaluator} yields 403
 *       {@code error.access.denied} (Requirement 7.2); an allow decision (including the ADMIN
 *       bypass) proceeds (Requirements 7.3, 7.4).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String ROLE_PREFIX = "ROLE_";

    private final ForemenPermissionEvaluator evaluator;
    private final PermissionResolver resolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // static resources etc. — no annotation to enforce (Req 5.2)
        }
        PermissionResolver.ResolvedPair pair = resolver.resolve(handlerMethod);
        if (pair == null) {
            return true; // Unguarded endpoint -> no matrix check (Req 5.1)
        }
        String roleCode = currentRoleCode();
        if (roleCode == null) {
            // Defensive 401: guarded endpoint reached without an authenticated principal
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        if (!evaluator.isAllowed(roleCode, pair.resource(), pair.operation())) {
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
