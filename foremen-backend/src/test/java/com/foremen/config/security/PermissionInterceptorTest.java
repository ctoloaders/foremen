package com.foremen.config.security;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.permission.PermissionCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example tests for {@link PermissionInterceptor} (task 5.2).
 *
 * <p>Exercises the interceptor with {@link MockHttpServletRequest}/{@link HandlerMethod} handlers
 * and a Mockito-mocked {@link ForemenPermissionEvaluator} (except the cache-miss case, which uses a
 * real evaluator + real {@link PermissionCache} over a counting {@link RoleDao}). Covers:</p>
 * <ul>
 *   <li>Method-level annotation is used (Requirement 4.3).</li>
 *   <li>A handler without the method annotation is treated as unannotated and proceeds without
 *       evaluation, with no type-level fallback (Requirement 4.4).</li>
 *   <li>A non-{@link HandlerMethod} handler proceeds without evaluation (Requirement 5.3).</li>
 *   <li>A cleared context on an annotated handler yields 401 {@code error.auth.unauthorized}
 *       (Requirement 7.2).</li>
 *   <li>A deny decision yields 403 {@code error.access.denied} (Requirement 6.1).</li>
 *   <li>A cache miss triggers exactly one database load (Requirement 8.1).</li>
 * </ul>
 */
class PermissionInterceptorTest {

    private final HttpServletRequest request = new MockHttpServletRequest();
    private final HttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void clearBefore() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAfter() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Requirement 4.3 — the method-level annotation is used
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uses the method-level @RequiresPermission to evaluate the required permission (4.3)")
    void usesMethodLevelAnnotation() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        when(evaluator.isAllowed("MANAGER", "PROJECTS", "READ")).thenReturn(true);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("MANAGER");

        boolean proceed = interceptor.preHandle(request, response, annotatedHandler());

        assertThat(proceed).isTrue();
        // The resource/operation came from the method-level annotation (PROJECTS/READ).
        verify(evaluator).isAllowed("MANAGER", "PROJECTS", "READ");
    }

    // ------------------------------------------------------------------
    // Requirement 4.4 — handler without a method annotation is unannotated (no type fallback)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("treats a handler whose method carries no @RequiresPermission as unannotated - proceeds, no type fallback (4.4)")
    void unannotatedMethodProceedsWithoutEvaluationEvenIfTypeAnnotated() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("MANAGER");

        // The declaring type carries @RequiresPermission but the method does not: no type-level fallback.
        boolean proceed = interceptor.preHandle(request, response, unannotatedMethodOnAnnotatedType());

        assertThat(proceed).isTrue();
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Requirement 5.3 — no annotation -> proceed without evaluation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("proceeds without evaluation when the handler method carries no annotation (5.3)")
    void plainUnannotatedHandlerProceedsWithoutEvaluation() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("WORKER");

        boolean proceed = interceptor.preHandle(request, response, plainHandler());

        assertThat(proceed).isTrue();
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("proceeds without evaluation for a non-HandlerMethod handler such as a static resource (5.3)")
    void nonHandlerMethodProceedsWithoutEvaluation() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("WORKER");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Requirement 7.2 — cleared context on annotated handler -> 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("raises 401 error.auth.unauthorized when the context holds no authenticated principal (7.2)")
    void clearedContextOnAnnotatedHandlerYields401() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        // Context intentionally cleared (no authentication).

        assertThatThrownBy(() -> interceptor.preHandle(request, response, annotatedHandler()))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.unauthorized");
                });
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("raises 401 for an anonymous authentication token on an annotated handler (7.2)")
    void anonymousTokenOnAnnotatedHandlerYields401() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, annotatedHandler()))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.unauthorized");
                });
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Requirement 6.1 — deny -> 403 error.access.denied
    // ------------------------------------------------------------------

    @Test
    @DisplayName("raises 403 error.access.denied when the evaluator denies an authenticated request (6.1)")
    void denyYields403() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        when(evaluator.isAllowed("WORKER", "PROJECTS", "READ")).thenReturn(false);
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("WORKER");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, annotatedHandler()))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
                });
    }

    // ------------------------------------------------------------------
    // Requirement 8.1 — cache miss triggers exactly one load
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cache miss on an annotated handler triggers exactly one database load (8.1)")
    void cacheMissTriggersExactlyOneLoad() {
        AtomicInteger loads = new AtomicInteger();
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.findByCode(eq("MANAGER"))).thenAnswer(inv -> {
            loads.incrementAndGet();
            return Optional.of(roleWith("MANAGER", "PROJECTS", List.of("READ")));
        });
        // Real evaluator + real dedicated cache: the first evaluation misses and loads once.
        ForemenPermissionEvaluator evaluator =
                new ForemenPermissionEvaluator(roleDao, new PermissionCache(new PermissionProperties(null)));
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator);
        authenticateAs("MANAGER");

        boolean proceed = interceptor.preHandle(request, response, annotatedHandler());

        assertThat(proceed).isTrue();
        assertThat(loads.get())
                .as("a cache miss for role 'MANAGER' must load from the database exactly once")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void authenticateAs(String roleCode) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "42", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    private HandlerMethod annotatedHandler() {
        return handlerFor(AnnotatedController.class, "secured");
    }

    private HandlerMethod plainHandler() {
        return handlerFor(PlainController.class, "open");
    }

    private HandlerMethod unannotatedMethodOnAnnotatedType() {
        return handlerFor(TypeAnnotatedController.class, "notAnnotated");
    }

    private HandlerMethod handlerFor(Class<?> type, String methodName) {
        try {
            Object bean = type.getDeclaredConstructor().newInstance();
            Method method = type.getMethod(methodName);
            return new HandlerMethod(bean, method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private RoleEntity roleWith(String code, String resourceCode, List<String> operations) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);

        ResourceEntity resource = new ResourceEntity();
        resource.setCode(resourceCode);

        List<OperationEntity> ops = new ArrayList<>();
        for (String opCode : operations) {
            OperationEntity op = new OperationEntity();
            op.setCode(opCode);
            ops.add(op);
        }

        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setResource(resource);
        rr.setOperations(ops);

        role.getRoleResources().add(rr);
        return role;
    }

    // --- Test controllers ---

    static class AnnotatedController {
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void secured() {
            // no-op
        }
    }

    static class PlainController {
        public void open() {
            // no-op; no annotation anywhere
        }
    }

    /**
     * A controller whose method carries no {@code @RequiresPermission}. {@code @RequiresPermission}
     * targets methods only, so there is no type-level annotation possible; this handler exercises
     * the "method carries no annotation -> unannotated, no fallback" path (4.4).
     */
    static class TypeAnnotatedController {
        public void notAnnotated() {
            // no-op
        }
    }
}
