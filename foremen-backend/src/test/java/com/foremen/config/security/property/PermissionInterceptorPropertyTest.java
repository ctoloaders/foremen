package com.foremen.config.security.property;

import com.foremen.config.security.PermissionInterceptor;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for the {@link PermissionInterceptor} (task 5.2).
 *
 * <p>Covers the design property assigned to this task:</p>
 * <ul>
 *   <li><b>Property 7: Role code is extracted from the ROLE_ authority</b>
 *       &mdash; Validates Requirements 5.4</li>
 * </ul>
 *
 * <p>The interceptor is exercised against a real {@link HandlerMethod} carrying a
 * {@code @RequiresPermission} annotation. The {@link ForemenPermissionEvaluator} is replaced by a
 * capturing stub that records the exact role code it was asked about, letting us assert the
 * interceptor stripped the {@code ROLE_} prefix from the authority and evaluated using precisely
 * that role code.</p>
 */
class PermissionInterceptorPropertyTest {

    @AfterTry
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // Feature: FOR-03-03-permission-evaluator, Property 7: Role code is extracted from the ROLE_ authority.
    // For any role code, when the SecurityContext holds an authenticated token whose single authority is
    // ROLE_<roleCode>, the PermissionInterceptor evaluates the request using exactly that role code (the
    // ROLE_ prefix is stripped and nothing else is passed to the evaluator).
    /**
     * <b>Validates: Requirements 5.4</b>
     */
    @Property(tries = 100)
    void roleCodeExtractedFromRoleAuthority(@ForAll("roleCodes") String roleCode) {
        AtomicReference<String> observedRoleCode = new AtomicReference<>();
        PermissionInterceptor interceptor = new PermissionInterceptor(capturingEvaluator(observedRoleCode));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "42", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));

        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, annotatedHandler());

        assertThat(observedRoleCode.get())
                .as("Interceptor must evaluate using exactly the role code carried by the ROLE_<code> authority")
                .isEqualTo(roleCode);
    }

    // --- Helpers ---

    /**
     * An evaluator stub that captures the role code it is asked about and always allows, so the
     * interceptor proceeds and we can inspect exactly which role code was extracted.
     */
    private ForemenPermissionEvaluator capturingEvaluator(AtomicReference<String> observedRoleCode) {
        return new ForemenPermissionEvaluator(null, null) {
            @Override
            public boolean isAllowed(String roleCode, String resource, String operation) {
                observedRoleCode.set(roleCode);
                return true;
            }
        };
    }

    /** A {@link HandlerMethod} over a method carrying {@code @RequiresPermission}. */
    private HandlerMethod annotatedHandler() {
        try {
            Method method = AnnotatedController.class.getMethod("secured");
            return new HandlerMethod(new AnnotatedController(), method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test controller whose method carries a method-level {@code @RequiresPermission}. */
    static class AnnotatedController {
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void secured() {
            // no-op; enforcement happens in the interceptor before this would run
        }
    }

    // --- Arbitrary Providers ---

    /**
     * Non-ADMIN role codes: non-blank alphanumeric strings. ADMIN is excluded because the bypass
     * short-circuits before the evaluator is consulted, so the capture would never fire.
     */
    @Provide
    Arbitrary<String> roleCodes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12)
                .filter(code -> !ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(code));
    }
}
