package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;

/**
 * FOR-05-06 (task 5.2) — ABAC mapping + startup-annotation verification for
 * {@link EstimateMarginsController} (the Margins tab read endpoint).
 *
 * <p>Mirrors {@link EstimateMaterialsControllerAbacTest} / {@link WorkerTypeControllerAbacTest}: a
 * fast, context-free reflection test that builds a {@link HandlerMethod} directly from a bare
 * controller instance ({@code null} collaborators are never dereferenced — only annotations are read)
 * and exercises the real {@link PermissionResolver} — the exact code path the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator} rely on. It
 * therefore needs neither a Spring context nor Testcontainers.
 *
 * <p>Asserts the two access-control guarantees the design mandates for the Margins tab plus the
 * guarantee the startup {@code PermissionAnnotationValidator} depends on:
 * <ol>
 *   <li><b>Reuses the shipped {@code ESTIMATE} resource, no new tab resource (design §B5, R4.1).</b>
 *       The controller carries class-level {@code @PermissionResource("ESTIMATE")}, so its handler
 *       resolves against the shipped estimate resource rather than a newly seeded one.</li>
 *   <li><b>Endpoint → (resource, operation) mapping (R4.1, R5.1).</b> The margins read resolves
 *       through the real {@link PermissionResolver#resolve(HandlerMethod)} to {@code ESTIMATE} READ —
 *       server-enforced, so a caller lacking READ is rejected regardless of the UI.</li>
 *   <li><b>Startup completeness (PermissionAnnotationValidator).</b> The handler classifies as
 *       {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check
 *       the startup validator performs. The handler is neither half-annotated nor UNGUARDED; a
 *       half-annotated {@link EstimateMarginsController} would fail application startup.</li>
 * </ol>
 *
 * <p>Validates: Requirements 4.1, 5.1
 */
@DisplayName("EstimateMarginsController ABAC mapping + startup completeness (reuses ESTIMATE)")
class EstimateMarginsControllerAbacTest {

    private static final String ESTIMATE = "ESTIMATE";
    private static final String READ = "READ";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborator is never dereferenced because only annotations are inspected.
     */
    private final EstimateMarginsController controller = new EstimateMarginsController(null);

    // --- class-level guarding: the reused ESTIMATE resource, no new one (design §B5, R4.1) ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"ESTIMATE\") — reuses the shipped resource (R4.1)")
    void controllerReusesEstimateResource() {
        PermissionResource annotation =
                EstimateMarginsController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("EstimateMarginsController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be the shipped ESTIMATE resource, not a new tab resource (R4.1)")
                .isEqualTo(ESTIMATE);
    }

    // --- endpoint → (resource, operation) mapping (R4.1, R5.1) ---

    @Test
    @DisplayName("GET /project/{id}/margins → (ESTIMATE, READ) (R4.1, R5.1)")
    void getMarginsIsEstimateRead() {
        assertResolvesTo(handlerFor("getMargins", Long.class), ESTIMATE, READ);
    }

    // --- startup completeness — the handler COMPLETE (PermissionAnnotationValidator) ---

    @Test
    @DisplayName("getMargins classifies COMPLETE (no half-annotation)")
    void getMarginsComplete() {
        assertComplete(handlerFor("getMargins", Long.class));
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = EstimateMarginsController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "EstimateMarginsController is missing expected handler " + methodName, e);
        }
    }

    private void assertResolvesTo(HandlerMethod handler, String resource, String operation) {
        PermissionResolver.ResolvedPair pair = resolver.resolve(handler);
        assertThat(pair)
                .as("%s must resolve to (%s, %s) — the interceptor would otherwise guard it wrongly "
                        + "or (null) leave it UNGUARDED", handler.getMethod().getName(), resource, operation)
                .isEqualTo(new PermissionResolver.ResolvedPair(resource, operation));
    }

    private void assertComplete(HandlerMethod handler) {
        assertThat(resolver.classifyCompleteness(handler))
                .as("%s must classify COMPLETE so PermissionAnnotationValidator does not fail startup "
                        + "(not half-annotated, not UNGUARDED)", handler.getMethod().getName())
                .isEqualTo(PermissionResolver.Completeness.COMPLETE);
    }
}
