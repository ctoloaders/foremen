package com.foremen.controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;

/**
 * FOR-06-10 (design §B2, §Testing — startup smoke; task 5.1) — ABAC mapping + startup-completeness
 * verification for {@link WorkerCostController}, plus the no-new-ABAC-resource / no-migration
 * assertions (R5.1, R5.2, R5.4).
 *
 * <p>Drives the <b>real</b> {@link PermissionResolver} — the exact code path both the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator} run — over the
 * controller's sole handler, asserting the guarantees the design and {@code entity-creation-rules}
 * step 3 mandate:
 *
 * <ol>
 *   <li><b>Class-level resource (R5.1).</b> The controller carries
 *       {@code @PermissionResource("WORK_REPORTS")} — it rides the FOR-06-01 resource (seeded by
 *       changeset 165), <b>not</b> a new one.</li>
 *   <li><b>Handler mapping (R5.1).</b> {@code GET /{projectId}/worker-costs} resolves to the pair the
 *       design mandates: {@code (WORK_REPORTS, READ)}. Because the request-time
 *       {@code PermissionInterceptor} enforces exactly this pair against the role matrix, the read is
 *       reachable by every role granted {@code WORK_REPORTS} READ — ADMIN / MANAGER / FOREMAN /
 *       FINANCIER (FINANCIER READ is seeded by FOR-06-01 changeset 165) — and a role without the grant
 *       is denied with 403.</li>
 *   <li><b>Startup completeness (R5.2).</b> The handler classifies
 *       {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)}, the exact per-handler check the
 *       startup {@code PermissionAnnotationValidator} performs — so it is never half-annotated
 *       ({@code RESOURCE_WITHOUT_OPERATION} / {@code OPERATION_WITHOUT_RESOURCE}) and a running app can
 *       never fail startup on this controller.</li>
 *   <li><b>No new ABAC resource / no migration (R5.1, R5.4).</b> FOR-06-10 is a pure read-only
 *       projection: the changelog head stays at <b>179</b> (the last changeset is FOR-06-08's
 *       {@code 179-seed-amendments-resource.xml}), and no FOR-06-10 {@code *-resource.xml} changeset
 *       was added — the WORK_REPORTS resource (incl. the FINANCIER READ grant) is already seeded by
 *       FOR-06-01 changeset 165, so this spec adds <b>no</b> changeset.</li>
 * </ol>
 *
 * <p>A fast, context-free reflection test (mirrors {@link AmendmentControllerAbacTest} /
 * {@code MarginMatrixControllerSmokeTest}): it builds a {@link HandlerMethod} from a bare controller
 * whose {@code null} collaborator is never dereferenced (only annotations are read), so it needs
 * neither a Spring context nor Testcontainers while asserting the identical logic the startup
 * validator relies on. The "no new resource" guarantee is asserted directly against the changeset tree.
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.4
 */
@Tag("Feature: FOR-06-10-worker-cost-rates, task 5.1: WorkerCostController ABAC + startup smoke")
@DisplayName("WorkerCostController ABAC mapping + startup completeness + no-new-resource")
class WorkerCostControllerAbacTest {

    private static final String WORK_REPORTS = "WORK_REPORTS";
    private static final String READ = "READ";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborator is never dereferenced because only annotations are inspected.
     */
    private final WorkerCostController controller = new WorkerCostController(null);

    // --- class-level guarding: rides the WORK_REPORTS resource, no new one (R5.1) ---------------

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"WORK_REPORTS\") — no new resource (R5.1)")
    void controllerRidesTheExistingWorkReportsResource() {
        PermissionResource annotation =
                WorkerCostController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("WorkerCostController must carry a class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("the worker-cost read rides the existing WORK_REPORTS resource; no new ABAC resource (R5.1)")
                .isEqualTo(WORK_REPORTS);
    }

    // --- handler → (resource, operation) mapping (R5.1) ----------------------------------------

    @Test
    @DisplayName("GET /{projectId}/worker-costs → (WORK_REPORTS, READ) — readable by ADMIN/MANAGER/FOREMAN/FINANCIER, 403 without the grant (R5.1, R5.4)")
    void workerCostsIsWorkReportsRead() {
        PermissionResolver.ResolvedPair pair = resolver.resolve(workerCostsHandler());

        assertThat(pair)
                .as("the worker-cost read must resolve to a concrete (resource, operation) pair; a null "
                        + "pair would leave it UNGUARDED")
                .isNotNull();
        assertThat(pair)
                .as("GET /{projectId}/worker-costs must resolve to (WORK_REPORTS, READ) — the interceptor "
                        + "then admits exactly the roles granted WORK_REPORTS READ (ADMIN/MANAGER/FOREMAN/"
                        + "FINANCIER) and denies the rest with 403 (R5.1, R5.4)")
                .isEqualTo(new PermissionResolver.ResolvedPair(WORK_REPORTS, READ));
    }

    // --- startup completeness — the handler is COMPLETE (PermissionAnnotationValidator) (R5.2) --

    @Test
    @DisplayName("the worker-costs handler classifies COMPLETE so startup never fails on it (R5.2)")
    void workerCostsHandlerClassifiesComplete() {
        assertThat(resolver.classifyCompleteness(workerCostsHandler()))
                .as("WorkerCostController.workerCosts must be a COMPLETE permission declaration so the "
                        + "startup PermissionAnnotationValidator never fails on it (not half-annotated) (R5.2)")
                .isEqualTo(PermissionResolver.Completeness.COMPLETE);
    }

    // --- no new ABAC resource / no migration: changelog head stays at 179 (R5.1, R5.4) ----------

    @Test
    @DisplayName("FOR-06-10 introduces NO new resource / changeset — changelog head stays at 179 (R5.1, R5.4)")
    void forThisSpecIntroducesNoNewChangeset() throws IOException {
        Path changesetsDir = resolveChangesetsDir();
        assertThat(Files.isDirectory(changesetsDir))
                .as("the Liquibase changesets directory must exist at %s", changesetsDir.toAbsolutePath())
                .isTrue();

        List<String> numberedChangesets;
        try (Stream<Path> files = Files.list(changesetsDir)) {
            numberedChangesets = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("^\\d+-.*\\.xml$"))
                    .collect(Collectors.toList());
        }

        int head = numberedChangesets.stream()
                .mapToInt(name -> Integer.parseInt(name.substring(0, name.indexOf('-'))))
                .max()
                .orElse(-1);

        // FOR-06-08's 179-seed-amendments-resource.xml is the last changeset; FOR-06-10 adds none, so
        // the head must still be 179. A head above 179 would mean a new changeset (potentially a
        // resource seed) was introduced, violating the "no new resource / no migration" contract.
        assertThat(head)
                .as("FOR-06-10 adds no Liquibase changeset — the changelog head must stay at 179 "
                        + "(found %s); the feature rides the existing WORK_REPORTS resource seeded by "
                        + "FOR-06-01 changeset 165, incl. the FINANCIER READ grant (R5.1, R5.4)", head)
                .isEqualTo(179);

        // Specifically: no resource-seed changeset numbered above 179 (what a new ABAC resource would
        // look like, e.g. an NNN-seed-<entity>-resource.xml per entity-creation-rules step 1).
        List<String> resourceSeedsAbove179 = numberedChangesets.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("-resource.xml"))
                .filter(name -> Integer.parseInt(name.substring(0, name.indexOf('-'))) > 179)
                .collect(Collectors.toList());
        assertThat(resourceSeedsAbove179)
                .as("FOR-06-10 must seed NO new resource — found resource-seed changeset(s) above 179: %s "
                        + "(R5.1)", resourceSeedsAbove179)
                .isEmpty();

        // And the FOR-06-01 WORK_REPORTS resource (which carries the FINANCIER READ grant) is still the
        // one the read rides — not a new resource invented here.
        String changelog = Files.readString(resolveChangelog());
        assertThat(changelog)
                .as("the WORK_REPORTS resource must still be seeded by the FOR-06-01 changeset 165")
                .contains("165-seed-work-reports-resource.xml");
        assertThat(changelog)
                .as("FOR-06-10 is a read-only projection — it must add no worker-cost changeset")
                .doesNotContain("worker-cost")
                .doesNotContain("FOR-06-10");
    }

    // --- helpers -------------------------------------------------------------------------------

    private HandlerMethod workerCostsHandler() {
        try {
            Method method = WorkerCostController.class.getMethod("workerCosts", Long.class);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "WorkerCostController is missing the expected handler workerCosts(Long)", e);
        }
    }

    /**
     * Resolves the Liquibase changesets directory from either the module root (when Gradle runs the
     * test from {@code foremen-backend/}) or the repo root, so the test is independent of the working
     * directory it is launched from.
     */
    private Path resolveChangesetsDir() {
        Path fromModuleRoot = Paths.get("database_files", "changesets");
        Path fromRepoRoot = Paths.get("foremen-backend", "database_files", "changesets");
        return Files.isDirectory(fromModuleRoot) ? fromModuleRoot : fromRepoRoot;
    }

    private Path resolveChangelog() {
        Path fromModule = Paths.get("database_files", "changelog.xml");
        return Files.exists(fromModule)
                ? fromModule
                : Paths.get("foremen-backend", "database_files", "changelog.xml");
    }
}
