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
import com.foremen.controller.model.ClassifyRequestModel;
import com.foremen.controller.model.RelinkRequestModel;
import com.foremen.dao.model.MatchStatus;

/**
 * FOR-06-04 task 8.2 — startup-annotation + no-new-ABAC-resource smoke for {@link PurchasesController}
 * (design §5.4; Requirements 7.1, 7.2).
 *
 * <p>Asserts, at the annotation/resolution level, the two guarantees the startup
 * {@code PermissionAnnotationValidator} and the request-time {@code PermissionInterceptor} depend on,
 * plus the spec's "rides the FOR-06-03 {@code PURCHASES} resource — adds none" guarantee:
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping (R7.1).</b> Each handler resolves through the
 *       real {@link PermissionResolver#resolve(HandlerMethod)} (the exact per-request code path) to
 *       the pair the design mandates: the reconciled list → {@code PURCHASES}/{@code READ}; the four
 *       reconciliation verbs (re-link / clear-match / classify / clear-classification) →
 *       {@code PURCHASES}/{@code UPDATE}. A READ-only caller resolves no UPDATE and cannot
 *       reconcile (R6.4).</li>
 *   <li><b>Startup completeness (R7.2, {@code PermissionAnnotationValidator}).</b> Because the
 *       controller inherits no {@code @PermissionOperation} from {@code AdminController}, every
 *       in-scope handler carries a method-level {@code @RequiresPermission}; each therefore classifies
 *       {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check
 *       the startup validator performs. No handler is half-annotated
 *       ({@code RESOURCE_WITHOUT_OPERATION} / {@code OPERATION_WITHOUT_RESOURCE}) and none is
 *       UNGUARDED, so a loading application context would not fail on this controller. A
 *       half-annotated controller would fail application startup.</li>
 *   <li><b>No new ABAC resource (R7.2).</b> The single FOR-06-04 changeset
 *       ({@code 170-add-procurement-line-unplanned-category.xml}) is a pure column + FK addition — it
 *       INSERTs into no ABAC seed table ({@code resources} / {@code operations} /
 *       {@code role_resources} / {@code role_resource_operations}); the reconciliation surface reuses
 *       the FOR-06-03 {@code PURCHASES} resource, which is exactly what the controller's class-level
 *       {@code @PermissionResource("PURCHASES")} declares.</li>
 * </ol>
 *
 * <p>This is a fast, context-free reflection test mirroring {@link ProcurementDocumentControllerAbacTest}
 * / {@link EstimateMaterialsControllerAbacTest}: it builds a {@link HandlerMethod} directly from a
 * bare controller instance ({@code null} collaborators are never dereferenced — only annotations are
 * read) and exercises the real {@link PermissionResolver}, so it needs neither a Spring context nor
 * Testcontainers while asserting the identical logic the startup validator relies on. The
 * "no new resource" guarantee is asserted directly against the changeset XML (the migration-run
 * companion {@code ProcurementLineUnplannedCategoryMigrationIntegrationTest} asserts it against the
 * live catalog).
 *
 * <p>Validates: Requirements 7.1, 7.2
 */
@DisplayName("PurchasesController ABAC mapping + startup completeness + no new ABAC resource")
class PurchasesControllerStartupSmokeTest {

    private static final String PURCHASES = "PURCHASES";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final PurchasesController controller = new PurchasesController(null, null);

    // --- class-level guarding: rides the FOR-06-03 PURCHASES resource, no new one (R7.2) ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"PURCHASES\") — reuses the FOR-06-03 resource (R7.2)")
    void controllerReusesPurchasesResource() {
        PermissionResource annotation =
                PurchasesController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("PurchasesController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be the FOR-06-03 PURCHASES resource, not a new one (R7.2)")
                .isEqualTo(PURCHASES);
    }

    // --- endpoint → (resource, operation) mapping (R7.1) ---

    @Test
    @DisplayName("GET /?projectId= (reconciled list) → (PURCHASES, READ)")
    void listIsPurchasesRead() {
        assertResolvesTo(
                handlerFor("list", Long.class, Long.class, MatchStatus.class, Long.class),
                PURCHASES, READ);
    }

    @Test
    @DisplayName("PUT /{id}/match (re-link) → (PURCHASES, UPDATE)")
    void relinkIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("relink", Long.class, RelinkRequestModel.class), PURCHASES, UPDATE);
    }

    @Test
    @DisplayName("DELETE /{id}/match (clear match) → (PURCHASES, UPDATE)")
    void clearMatchIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("clearMatch", Long.class), PURCHASES, UPDATE);
    }

    @Test
    @DisplayName("PUT /{id}/classify → (PURCHASES, UPDATE)")
    void classifyIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("classify", Long.class, ClassifyRequestModel.class), PURCHASES, UPDATE);
    }

    @Test
    @DisplayName("DELETE /{id}/classify (clear classification) → (PURCHASES, UPDATE)")
    void clearClassificationIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("clearClassification", Long.class), PURCHASES, UPDATE);
    }

    // --- startup completeness — every handler COMPLETE (PermissionAnnotationValidator), R7.2 ---

    @Test
    @DisplayName("every handler classifies COMPLETE (no half-annotation, none UNGUARDED) — R7.2")
    void everyHandlerComplete() {
        assertComplete(handlerFor("list", Long.class, Long.class, MatchStatus.class, Long.class));
        assertComplete(handlerFor("relink", Long.class, RelinkRequestModel.class));
        assertComplete(handlerFor("clearMatch", Long.class));
        assertComplete(handlerFor("classify", Long.class, ClassifyRequestModel.class));
        assertComplete(handlerFor("clearClassification", Long.class));
    }

    // --- no new ABAC resource / operation / role grant is seeded by FOR-06-04 (R7.2) ---

    @Test
    @DisplayName("the FOR-06-04 changeset (170) seeds NO new resource/operation/role grant — rides PURCHASES (R7.2)")
    void forThisSpecNoNewResourceOperationOrRoleGrantIsSeeded() throws IOException {
        String changeset = readChangeset170().toLowerCase(Locale.ROOT);

        // The single FOR-06-04 changeset must be a pure column + FK addition — it must not INSERT into
        // any ABAC seed table, which is how a new resource/operation/role grant would be introduced.
        assertThat(changeset)
                .as("FOR-06-04 changeset 170 must be a schema-only column addition (R7.2)")
                .contains("addcolumn")
                .contains("unplanned_category_id");

        for (String abacTable : List.of(
                "into resources", "into operations", "into role_resources", "into role_resource_operations",
                "tablename=\"resources\"", "tablename=\"operations\"",
                "tablename=\"role_resources\"", "tablename=\"role_resource_operations\"")) {
            assertThat(changeset)
                    .as("FOR-06-04 must NOT seed a new ABAC resource/operation/role grant "
                            + "(found reference to '%s' in changeset 170) — R7.2 rides PURCHASES", abacTable)
                    .doesNotContain(abacTable);
        }
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = PurchasesController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "PurchasesController is missing expected handler " + methodName, e);
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
     * Reads the FOR-06-04 changeset XML from the source tree, resolving the path from the module root
     * so the test is independent of the working directory Gradle runs it from.
     */
    private String readChangeset170() throws IOException {
        Path fromModuleRoot = Paths.get(
                "database_files", "changesets", "170-add-procurement-line-unplanned-category.xml");
        Path fromRepoRoot = Paths.get(
                "foremen-backend", "database_files", "changesets",
                "170-add-procurement-line-unplanned-category.xml");
        Path changeset = Files.exists(fromModuleRoot) ? fromModuleRoot : fromRepoRoot;
        assertThat(Files.exists(changeset))
                .as("FOR-06-04 changeset 170 must exist at %s", changeset.toAbsolutePath())
                .isTrue();
        return Files.readString(changeset);
    }
}
