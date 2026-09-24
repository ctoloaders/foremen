package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.ApplyAssignmentsRequest;
import com.foremen.controller.model.ApplyPackageRequest;
import com.foremen.controller.model.ApplyWorkRequest;
import com.foremen.controller.model.RecomputeFinishingRequest;

/**
 * ABAC + startup-annotation verification for {@link EstimateMatrixController} (FOR-05-05 task 6.3).
 *
 * <p>Asserts, at the annotation/resolution level, the two guarantees the runtime ABAC stack and the
 * startup {@code PermissionAnnotationValidator} depend on:
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping.</b> Each handler resolves through the real
 *       {@link PermissionResolver#resolve(HandlerMethod)} (the exact code path the
 *       {@code PermissionInterceptor} runs per request) to the pair the design mandates: the matrix
 *       read and the {@code apply-package} / {@code apply-work} / {@code recompute-finishing}
 *       <b>calculate/preview</b> endpoints (which persist nothing, R15.6) require {@code ESTIMATE}
 *       READ, while only the batched Save {@code POST /assignments} requires {@code ESTIMATE} UPDATE
 *       (R15.7).</li>
 *   <li><b>Startup completeness.</b> Each handler classifies as {@link
 *       PermissionResolver.Completeness#COMPLETE} via {@link
 *       PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check the
 *       {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs at startup
 *       (R19.1). No handler is {@code RESOURCE_WITHOUT_OPERATION} / {@code OPERATION_WITHOUT_RESOURCE}
 *       (half-annotated) and, because every handler resolves to a non-null pair, none is UNGUARDED.</li>
 * </ol>
 *
 * <p>This is a fast, context-free reflection test mirroring
 * {@code PermissionResolverCombinedResolutionPropertyTest} and {@code ControllerResourceMappingTest}:
 * it builds a {@link HandlerMethod} directly from a bare controller instance (collaborators are
 * {@code null} — they are never invoked, only their annotations are read) and exercises the real
 * {@link PermissionResolver}. It therefore needs neither a Spring context nor Testcontainers, while
 * still asserting the identical logic the startup validator and the request interceptor rely on. The
 * companion whole-app boot is covered by {@code EstimateControllersStartupIntegrationTest}.
 *
 * <p>Validates: Requirements 15.6, 15.7, 19.1
 */
@DisplayName("EstimateMatrixController ABAC mapping + startup completeness")
class EstimateMatrixControllerAbacTest {

    private static final String ESTIMATE = "ESTIMATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final EstimateMatrixController controller = new EstimateMatrixController(null, null);

    // --- class-level guarding ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"ESTIMATE\")")
    void controllerCarriesEstimateResource() {
        PermissionResource annotation =
                EstimateMatrixController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("EstimateMatrixController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code")
                .isEqualTo(ESTIMATE);
    }

    // --- endpoint → (resource, operation) mapping (R15.6, R15.7) ---

    @Test
    @DisplayName("GET /project/{id}/matrix → (ESTIMATE, READ)")
    void getMatrixIsEstimateRead() {
        assertResolvesTo(handlerFor("getMatrix", Long.class), ESTIMATE, READ);
    }

    @Test
    @DisplayName("POST /project/{id}/apply-package (calculate, persists nothing) → (ESTIMATE, READ)")
    void applyPackageIsEstimateRead() {
        assertResolvesTo(handlerFor("applyPackage", Long.class, ApplyPackageRequest.class), ESTIMATE, READ);
    }

    @Test
    @DisplayName("POST /project/{id}/apply-work (calculate, persists nothing) → (ESTIMATE, READ)")
    void applyWorkIsEstimateRead() {
        assertResolvesTo(handlerFor("applyWork", Long.class, ApplyWorkRequest.class), ESTIMATE, READ);
    }

    @Test
    @DisplayName("POST /project/{id}/recompute-finishing (calculate, persists nothing) → (ESTIMATE, READ)")
    void recomputeFinishingIsEstimateRead() {
        assertResolvesTo(
                handlerFor("recomputeFinishing", Long.class, RecomputeFinishingRequest.class), ESTIMATE, READ);
    }

    @Test
    @DisplayName("POST /project/{id}/assignments (batched Save, persists) → (ESTIMATE, UPDATE)")
    void saveAssignmentsIsEstimateUpdate() {
        assertResolvesTo(
                handlerFor("saveAssignments", Long.class, ApplyAssignmentsRequest.class), ESTIMATE, UPDATE);
    }

    // --- startup completeness — every handler COMPLETE (R19.1) ---

    @Test
    @DisplayName("getMatrix classifies COMPLETE")
    void getMatrixComplete() {
        assertComplete(handlerFor("getMatrix", Long.class));
    }

    @Test
    @DisplayName("applyPackage classifies COMPLETE")
    void applyPackageComplete() {
        assertComplete(handlerFor("applyPackage", Long.class, ApplyPackageRequest.class));
    }

    @Test
    @DisplayName("applyWork classifies COMPLETE")
    void applyWorkComplete() {
        assertComplete(handlerFor("applyWork", Long.class, ApplyWorkRequest.class));
    }

    @Test
    @DisplayName("recomputeFinishing classifies COMPLETE")
    void recomputeFinishingComplete() {
        assertComplete(handlerFor("recomputeFinishing", Long.class, RecomputeFinishingRequest.class));
    }

    @Test
    @DisplayName("saveAssignments classifies COMPLETE")
    void saveAssignmentsComplete() {
        assertComplete(handlerFor("saveAssignments", Long.class, ApplyAssignmentsRequest.class));
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = EstimateMatrixController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "EstimateMatrixController is missing expected handler " + methodName, e);
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
