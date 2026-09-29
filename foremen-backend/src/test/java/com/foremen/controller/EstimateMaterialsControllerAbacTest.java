package com.foremen.controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.estimate.materials.MaterialsReserveRequest;

/**
 * ABAC + startup-annotation verification for {@link EstimateMaterialsController} (FOR-05-05b task 6.2).
 *
 * <p>Asserts, at the annotation/resolution level, the three access-control guarantees the design
 * mandates for the Materials tab and the guarantee the startup {@code PermissionAnnotationValidator}
 * depends on:
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping (R8.1, R8.3).</b> Each handler resolves through
 *       the real {@link PermissionResolver#resolve(HandlerMethod)} (the exact code path the
 *       {@code PermissionInterceptor} runs per request) to the pair the design mandates: the materials
 *       read requires {@code ESTIMATE} READ (R8.1), and the reserve PUT requires {@code ESTIMATE}
 *       UPDATE — server-enforced, so a caller lacking UPDATE is rejected regardless of the UI (R8.3).</li>
 *   <li><b>No new ABAC resource / operation / role grant (R8.4).</b> The single FOR-05-05b changeset
 *       ({@code 124-add-estimate-materials-reserve-map.xml}) adds only a column and seeds NO row into
 *       {@code resources} / {@code operations} / {@code role_resources} / {@code role_resource_operations};
 *       the tab reuses the shipped {@code ESTIMATE} resource, which is what the controller's
 *       {@code @PermissionResource("ESTIMATE")} declares.</li>
 *   <li><b>Startup completeness (R8, PermissionAnnotationValidator).</b> Each handler classifies as
 *       {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check
 *       the {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs at
 *       startup. No handler is half-annotated ({@code RESOURCE_WITHOUT_OPERATION} /
 *       {@code OPERATION_WITHOUT_RESOURCE}) and, because every handler resolves to a non-null pair,
 *       none is UNGUARDED. A half-annotated {@link EstimateMaterialsController} would fail application
 *       startup.</li>
 * </ol>
 *
 * <p>This is a fast, context-free reflection test mirroring {@link EstimateMatrixControllerAbacTest}:
 * it builds a {@link HandlerMethod} directly from a bare controller instance ({@code null}
 * collaborators are never dereferenced — only annotations are read) and exercises the real
 * {@link PermissionResolver}, so it needs neither a Spring context nor Testcontainers while asserting
 * the identical logic the startup validator and the request interceptor rely on. The R8.4 "no new
 * resource" guarantee is asserted directly against the changeset XML.
 *
 * <p>Validates: Requirements 8.1, 8.3, 8.4
 */
@DisplayName("EstimateMaterialsController ABAC mapping + startup completeness + no new resource")
class EstimateMaterialsControllerAbacTest {

    private static final String ESTIMATE = "ESTIMATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final EstimateMaterialsController controller = new EstimateMaterialsController(null, null);

    // --- class-level guarding: the reused ESTIMATE resource, no new one (R8.4) ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"ESTIMATE\") — reuses the shipped resource (R8.4)")
    void controllerReusesEstimateResource() {
        PermissionResource annotation =
                EstimateMaterialsController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("EstimateMaterialsController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be the shipped ESTIMATE resource, not a new one (R8.4)")
                .isEqualTo(ESTIMATE);
    }

    // --- endpoint → (resource, operation) mapping (R8.1, R8.3) ---

    @Test
    @DisplayName("GET /project/{id}/materials → (ESTIMATE, READ) (R8.1)")
    void getMaterialsIsEstimateRead() {
        assertResolvesTo(handlerFor("getMaterials", Long.class), ESTIMATE, READ);
    }

    @Test
    @DisplayName("PUT /project/{id}/materials/reserve → (ESTIMATE, UPDATE) — server-enforced (R8.3)")
    void saveReserveIsEstimateUpdate() {
        assertResolvesTo(
                handlerFor("saveReserve", Long.class, MaterialsReserveRequest.class), ESTIMATE, UPDATE);
    }

    // --- startup completeness — every handler COMPLETE (PermissionAnnotationValidator) ---

    @Test
    @DisplayName("getMaterials classifies COMPLETE")
    void getMaterialsComplete() {
        assertComplete(handlerFor("getMaterials", Long.class));
    }

    @Test
    @DisplayName("saveReserve classifies COMPLETE")
    void saveReserveComplete() {
        assertComplete(handlerFor("saveReserve", Long.class, MaterialsReserveRequest.class));
    }

    // --- no new ABAC resource / operation / role grant is seeded by FOR-05-05b (R8.4) ---

    @Test
    @DisplayName("the FOR-05-05b changeset (124) seeds NO new resource/operation/role grant — reuses ESTIMATE (R8.4)")
    void forThisSpecNoNewResourceOperationOrRoleGrantIsSeeded() throws IOException {
        String changeset = readChangeset124().toLowerCase(Locale.ROOT);

        // The single FOR-05-05b changeset must be a pure column addition — it must not INSERT into any
        // ABAC seed table (resources / operations / role_resources / role_resource_operations), which
        // is how a new resource/operation/role grant would be introduced.
        assertThat(changeset)
                .as("FOR-05-05b changeset 124 must be a schema-only column addition (R8.4)")
                .contains("addcolumn")
                .contains("materials_reserve_map");

        for (String abacTable : List.of(
                "into resources", "into operations", "into role_resources", "into role_resource_operations",
                "tablename=\"resources\"", "tablename=\"operations\"",
                "tablename=\"role_resources\"", "tablename=\"role_resource_operations\"")) {
            assertThat(changeset)
                    .as("FOR-05-05b must NOT seed a new ABAC resource/operation/role grant "
                            + "(found reference to '%s' in changeset 124) — R8.4 reuses ESTIMATE", abacTable)
                    .doesNotContain(abacTable);
        }
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = EstimateMaterialsController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "EstimateMaterialsController is missing expected handler " + methodName, e);
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

    /**
     * Reads the FOR-05-05b changeset XML from the source tree. Resolves the path from the module root
     * so the test is independent of the working directory Gradle runs it from.
     */
    private String readChangeset124() throws IOException {
        Path fromModuleRoot = Paths.get(
                "database_files", "changesets", "124-add-estimate-materials-reserve-map.xml");
        Path fromRepoRoot = Paths.get(
                "foremen-backend", "database_files", "changesets", "124-add-estimate-materials-reserve-map.xml");
        Path changeset = Files.exists(fromModuleRoot) ? fromModuleRoot : fromRepoRoot;
        assertThat(Files.exists(changeset))
                .as("FOR-05-05b changeset 124 must exist at %s", changeset.toAbsolutePath())
                .isTrue();
        return Files.readString(changeset);
    }
}
