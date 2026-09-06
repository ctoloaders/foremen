package com.foremen.config.security;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Branch unit tests for {@link PermissionInterceptor#preHandle} (task 4.3).
 *
 * <p>The interceptor now delegates {@code (resource, operation)} derivation to the
 * {@link PermissionResolver}; both the resolver and the {@link ForemenPermissionEvaluator} are
 * stubbed with Mockito so each {@code preHandle} branch is exercised in isolation:</p>
 * <ul>
 *   <li>A non-{@link HandlerMethod} handler proceeds without touching the resolver or evaluator
 *       (Requirement 5.2).</li>
 *   <li>An Unguarded handler ({@code resolve} returns {@code null}) proceeds without evaluation
 *       (Requirement 5.2 / 5.1).</li>
 *   <li>A resolved pair with no authenticated principal yields 401 {@code error.auth.unauthorized}
 *       (Requirement 7.1).</li>
 *   <li>A resolved pair the evaluator denies yields 403 {@code error.access.denied}
 *       (Requirement 7.2).</li>
 *   <li>A resolved pair the evaluator allows proceeds (Requirement 7.3).</li>
 * </ul>
 */
class PermissionInterceptorTest {

    private final HttpServletRequest request = new MockHttpServletRequest();
    private final HttpServletResponse response = new MockHttpServletResponse();

    private ForemenPermissionEvaluator evaluator;
    private PermissionResolver resolver;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        evaluator = mock(ForemenPermissionEvaluator.class);
        resolver = mock(PermissionResolver.class);
        interceptor = new PermissionInterceptor(evaluator, resolver);
    }

    @AfterEach
    void clearAfter() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Requirement 5.2 — non-HandlerMethod handler proceeds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("proceeds without resolution or evaluation for a non-HandlerMethod handler (5.2)")
    void nonHandlerMethodProceeds() {
        authenticateAs("WORKER");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        verifyNoInteractions(resolver);
        verifyNoInteractions(evaluator);
    }

    // ------------------------------------------------------------------
    // Requirement 5.2 / 5.1 — Unguarded (resolve returns null) proceeds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("proceeds without evaluation when the resolver classifies the handler as Unguarded (5.2)")
    void unguardedHandlerProceeds() {
        HandlerMethod handler = handler();
        when(resolver.resolve(handler)).thenReturn(null);
        authenticateAs("WORKER");

        boolean proceed = interceptor.preHandle(request, response, handler);

        assertThat(proceed).isTrue();
        verify(resolver).resolve(handler);
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Requirement 7.1 — resolved pair + no principal -> 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("raises 401 error.auth.unauthorized when a pair resolves but no principal is present (7.1)")
    void resolvedPairWithNoPrincipalYields401() {
        HandlerMethod handler = handler();
        when(resolver.resolve(handler)).thenReturn(new PermissionResolver.ResolvedPair("USERS", "READ"));
        // Context intentionally left cleared (no authentication).

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.unauthorized");
                });
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("raises 401 for an anonymous authentication token when a pair resolves (7.1)")
    void resolvedPairWithAnonymousTokenYields401() {
        HandlerMethod handler = handler();
        when(resolver.resolve(handler)).thenReturn(new PermissionResolver.ResolvedPair("USERS", "READ"));
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.unauthorized");
                });
        verify(evaluator, never()).isAllowed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Requirement 7.2 — resolved pair + deny -> 403
    // ------------------------------------------------------------------

    @Test
    @DisplayName("raises 403 error.access.denied when the evaluator denies the resolved pair (7.2)")
    void resolvedPairDeniedYields403() {
        HandlerMethod handler = handler();
        when(resolver.resolve(handler)).thenReturn(new PermissionResolver.ResolvedPair("USERS", "READ"));
        when(evaluator.isAllowed("WORKER", "USERS", "READ")).thenReturn(false);
        authenticateAs("WORKER");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
                });
        verify(evaluator).isAllowed("WORKER", "USERS", "READ");
    }

    // ------------------------------------------------------------------
    // Requirement 7.3 — resolved pair + allow -> proceed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("proceeds when the evaluator allows the resolved pair (7.3)")
    void resolvedPairAllowedProceeds() {
        HandlerMethod handler = handler();
        when(resolver.resolve(handler)).thenReturn(new PermissionResolver.ResolvedPair("USERS", "READ"));
        when(evaluator.isAllowed("MANAGER", "USERS", "READ")).thenReturn(true);
        authenticateAs("MANAGER");

        boolean proceed = interceptor.preHandle(request, response, handler);

        assertThat(proceed).isTrue();
        verify(evaluator).isAllowed("MANAGER", "USERS", "READ");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void authenticateAs(String roleCode) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "42", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    private HandlerMethod handler() {
        try {
            Method method = SampleController.class.getMethod("handle");
            return new HandlerMethod(new SampleController(), method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal handler bean; the resolver is stubbed, so no real annotations are needed. */
    static class SampleController {
        public void handle() {
            // no-op
        }
    }
}
