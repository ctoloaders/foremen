package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.AddLineRequest;

/**
 * FOR-06-08 (Requirements 1.5, 3.7, 9.1, 9.4, 10.3; task 6.1) — ABAC mapping + startup-completeness
 * verification for {@link AmendmentController}.
 *
 * <p>Drives the <b>real</b> {@link PermissionResolver} — the exact code path both the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator} run — over the
 * controller's handlers, asserting the three guarantees the design and {@code entity-creation-rules}
 * step 3 mandate:
 *
 * <ol>
 *   <li><b>Class-level resource (R9.1).</b> The controller carries
 *       {@code @PermissionResource("AMENDMENTS")} — the exact code task 8.1 seeds.</li>
 *   <li><b>Bespoke-handler mapping (R1.5, R3.7, R10.3).</b> Each bespoke handler resolves to the
 *       design's {@code (resource, operation)} pair: {@code GET /open} → {@code (AMENDMENTS, READ)};
 *       {@code POST /{id}/lines} → {@code (AMENDMENTS, UPDATE)}; {@code DELETE /{id}/lines/{lineId}}
 *       and the overridden {@code DELETE /{id}} → {@code (AMENDMENTS, DELETE)}.</li>
 *   <li><b>Startup completeness (R9.4).</b> Every handler — the bespoke ones and a representative
 *       inherited CRUD {@code default} (the generic {@code create}, class-resource +
 *       method-{@code @PermissionOperation}) — classifies {@link PermissionResolver.Completeness#COMPLETE}
 *       via {@link PermissionResolver#classifyCompleteness(HandlerMethod)}, the exact per-handler
 *       check the {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs.
 *       No handler is half-annotated ({@code RESOURCE_WITHOUT_OPERATION} /
 *       {@code OPERATION_WITHOUT_RESOURCE}), so a running app can never fail startup on this
 *       controller.</li>
 * </ol>
 *
 * <p>A fast, context-free reflection test (mirrors {@link EstimateMaterialsControllerAbacTest}): it
 * builds a {@link HandlerMethod} from a bare controller instance whose {@code null} collaborators are
 * never dereferenced (only annotations are read), so it needs neither a Spring context nor
 * Testcontainers while asserting the identical logic the startup validator relies on.
 *
 * <p>Validates: Requirements 1.5, 3.7, 9.1, 9.4, 10.3
 */
@DisplayName("AmendmentController ABAC mapping + startup completeness")
class AmendmentControllerAbacTest {

    private static final String AMENDMENTS = "AMENDMENTS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final AmendmentController controller = new AmendmentController(null, null, null);

    // --- class-level guarding: the AMENDMENTS resource (R9.1) ----------------------------------

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"AMENDMENTS\") (R9.1)")
    void controllerCarriesAmendmentsResource() {
        PermissionResource annotation =
                AmendmentController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("AmendmentController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be exactly AMENDMENTS (R9.1, matches task 8.1 seed)")
                .isEqualTo(AMENDMENTS);
    }

    // --- bespoke-handler → (resource, operation) mapping (R1.5, R3.7, R10.3) --------------------

    @Test
    @DisplayName("GET /open → (AMENDMENTS, READ) (R1.5)")
    void getOpenIsAmendmentsRead() {
        assertResolvesTo(handlerFor("getOpen", Long.class), AMENDMENTS, READ);
    }

    @Test
    @DisplayName("POST /{id}/lines → (AMENDMENTS, UPDATE) (R3.7)")
    void addLineIsAmendmentsUpdate() {
        assertResolvesTo(
                handlerFor("addLine", Long.class, Long.class, AddLineRequest.class),
                AMENDMENTS, UPDATE);
    }

    @Test
    @DisplayName("DELETE /{id}/lines/{lineId} → (AMENDMENTS, DELETE) (R10.3)")
    void deleteLineIsAmendmentsDelete() {
        assertResolvesTo(handlerFor("deleteLine", Long.class, Long.class), AMENDMENTS, DELETE);
    }

    @Test
    @DisplayName("overridden DELETE /{id} → (AMENDMENTS, DELETE) (R10.3)")
    void deleteAmendmentIsAmendmentsDelete() {
        assertResolvesTo(handlerFor("deleteById", Long.class), AMENDMENTS, DELETE);
    }

    @Test
    @DisplayName("inherited CRUD create → (AMENDMENTS, CREATE) (class resource + method operation)")
    void inheritedCreateIsAmendmentsCreate() {
        assertResolvesTo(handlerFor("create", Object.class), AMENDMENTS, CREATE);
    }

    // --- startup completeness — every handler COMPLETE (PermissionAnnotationValidator) (R9.4) ---

    @Test
    @DisplayName("every AmendmentController handler classifies COMPLETE (R9.4)")
    void everyHandlerClassifiesComplete() {
        assertComplete(handlerFor("getOpen", Long.class));
        assertComplete(handlerFor("addLine", Long.class, Long.class, AddLineRequest.class));
        assertComplete(handlerFor("deleteLine", Long.class, Long.class));
        assertComplete(handlerFor("deleteById", Long.class));
        // A representative inherited CRUD default (class @PermissionResource + method
        // @PermissionOperation) — proves the inherited handlers are complete too.
        assertComplete(handlerFor("create", Object.class));
    }

    // --- helpers -------------------------------------------------------------------------------

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = AmendmentController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "AmendmentController is missing expected handler " + methodName, e);
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
