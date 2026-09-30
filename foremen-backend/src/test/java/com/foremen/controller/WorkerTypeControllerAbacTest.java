package com.foremen.controller;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;

/**
 * FOR-05-06 (task 2.2) — ABAC read/write gating + startup-annotation verification for
 * {@link WorkerTypeController} (ABAC resource {@code WORKER_TYPES}).
 *
 * <p>Mirrors {@link EstimateMaterialsControllerAbacTest}: a fast, context-free reflection test that
 * builds a {@link HandlerMethod} from a bare controller instance ({@code null} collaborators are never
 * dereferenced — only annotations are read) and exercises the real {@link PermissionResolver} — the
 * exact code path the request-time {@code PermissionInterceptor} and the startup
 * {@code PermissionAnnotationValidator} rely on. It therefore needs neither a Spring context nor
 * Testcontainers.
 *
 * <p>Asserts:
 * <ol>
 *   <li><b>Class-level resource (R1.2).</b> The controller carries
 *       {@code @PermissionResource("WORKER_TYPES")}, so every inherited CRUD handler resolves against
 *       the {@code WORKER_TYPES} resource.</li>
 *   <li><b>Endpoint → (resource, operation) mapping (R1.2).</b> Reads (find / findById) resolve to
 *       {@code (WORKER_TYPES, READ)}; writes (create → CREATE, update → UPDATE, deleteById → DELETE)
 *       resolve to the corresponding write operation — each server-enforced, so a caller lacking the
 *       operation is rejected regardless of the UI. The read/write split is what gives the "read for
 *       margin-viewing roles, full CRUD for ADMIN" gating its teeth.</li>
 *   <li><b>Startup completeness (R1.2, PermissionAnnotationValidator).</b> Each handler classifies as
 *       {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check
 *       the {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs at
 *       startup. No handler is half-annotated; a half-annotated {@link WorkerTypeController} would
 *       fail application startup.</li>
 * </ol>
 *
 * <p>Validates: Requirements 1.2, 1.5
 */
@DisplayName("WorkerTypeController ABAC read/write gating + startup completeness (WORKER_TYPES)")
class WorkerTypeControllerAbacTest {

    private static final String WORKER_TYPES = "WORKER_TYPES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final WorkerTypeController controller = new WorkerTypeController(null, null, null);

    // --- class-level guarding: the new WORKER_TYPES resource (R1.2) ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"WORKER_TYPES\") (R1.2)")
    void controllerDeclaresWorkerTypesResource() {
        PermissionResource annotation = WorkerTypeController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("WorkerTypeController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be the new WORKER_TYPES resource (R1.2)")
                .isEqualTo(WORKER_TYPES);
    }

    // --- read gating → (WORKER_TYPES, READ) ---

    @Test
    @DisplayName("GET /api/worker-types (find) → (WORKER_TYPES, READ)")
    void findIsWorkerTypesRead() {
        assertResolvesTo(handlerFor("find", org.springframework.data.domain.Pageable.class, String.class),
                WORKER_TYPES, READ);
    }

    @Test
    @DisplayName("GET /api/worker-types/{id} (findById) → (WORKER_TYPES, READ)")
    void findByIdIsWorkerTypesRead() {
        assertResolvesTo(handlerFor("findById", Object.class), WORKER_TYPES, READ);
    }

    // --- write gating → (WORKER_TYPES, {CREATE,UPDATE,DELETE}) ---

    @Test
    @DisplayName("POST /api/worker-types (create) → (WORKER_TYPES, CREATE) — server-enforced")
    void createIsWorkerTypesCreate() {
        assertResolvesTo(handlerFor("create", Object.class), WORKER_TYPES, CREATE);
    }

    @Test
    @DisplayName("PUT /api/worker-types/{id} (update) → (WORKER_TYPES, UPDATE) — server-enforced")
    void updateIsWorkerTypesUpdate() {
        assertResolvesTo(handlerFor("update", Object.class, Object.class), WORKER_TYPES, UPDATE);
    }

    @Test
    @DisplayName("DELETE /api/worker-types/{id} (deleteById) → (WORKER_TYPES, DELETE) — server-enforced")
    void deleteIsWorkerTypesDelete() {
        assertResolvesTo(handlerFor("deleteById", Object.class), WORKER_TYPES, DELETE);
    }

    // --- startup completeness — every CRUD handler COMPLETE (PermissionAnnotationValidator) ---

    @Test
    @DisplayName("every WorkerTypeController CRUD handler classifies COMPLETE (no half-annotation)")
    void allCrudHandlersComplete() {
        List<HandlerMethod> handlers = List.of(
                handlerFor("find", org.springframework.data.domain.Pageable.class, String.class),
                handlerFor("findById", Object.class),
                handlerFor("getCount", String.class),
                handlerFor("create", Object.class),
                handlerFor("createBulk", List.class),
                handlerFor("update", Object.class, Object.class),
                handlerFor("deleteById", Object.class),
                handlerFor("setPropertiesToNull", Object.class, Set.class));
        handlers.forEach(this::assertComplete);
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            // The CRUD handlers are inherited default methods on the AdminController interface; their
            // generic type parameters erase to Object/List/Set/Pageable on WorkerTypeController.
            Method method = WorkerTypeController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "WorkerTypeController is missing expected handler " + methodName, e);
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
