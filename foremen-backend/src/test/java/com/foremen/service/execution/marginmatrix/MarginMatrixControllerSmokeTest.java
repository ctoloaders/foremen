package com.foremen.service.execution.marginmatrix;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.MarginMatrixController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-06 (design §B3, §Testing — startup smoke; task 6.2) — startup-annotation smoke for
 * {@link MarginMatrixController} plus the no-new-ABAC-resource / no-migration assertions (R6.1, R6.2).
 *
 * <p>Rather than boot a full Spring context, this drives the real
 * {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact routine the startup
 * {@code PermissionAnnotationValidator} runs over every handler — against the controller's real
 * {@code getMatrix} handler. Because the handler carries a method-level
 * {@link RequiresPermission @RequiresPermission(WORK_REPORTS, READ)}, the resolver must classify it
 * {@code COMPLETE}, so the controller can never be the cause of a {@code PermissionAnnotationValidator}
 * startup failure (R6.2) — no half-annotation degrades it into an unguarded endpoint.
 *
 * <p>It also pins the spec's deliberate schema-free, resource-free shape (R6.1): the controller rides
 * the existing {@code WORK_REPORTS} resource (seeded by FOR-06-01, changeset 165) — <b>no new ABAC
 * resource</b> — and <b>no Liquibase changeset</b> was added for this spec (the changelog has no
 * {@code margin-matrix} / FOR-06-06 include). Mirrors the sibling
 * {@code MaterialsMatrixControllerSmokeTest} (FOR-06-05).
 */
class MarginMatrixControllerSmokeTest {

    private final PermissionResolver resolver = new PermissionResolver();

    // --- Startup-annotation smoke (R6.2) -------------------------------------------------------

    @Test
    void getMatrixHandlerIsClassifiedCompleteByTheStartupValidator() throws NoSuchMethodException {
        HandlerMethod handler = matrixHandler();

        // The exact classification the SmartInitializingSingleton PermissionAnnotationValidator runs:
        // a @RequiresPermission handler is always COMPLETE, so startup never fails on this controller.
        assertThat(resolver.classifyCompleteness(handler))
                .as("MarginMatrixController.getMatrix must be a COMPLETE permission declaration (R6.2)")
                .isEqualTo(PermissionResolver.Completeness.COMPLETE);
    }

    @Test
    void getMatrixResolvesToWorkReportsRead() throws NoSuchMethodException {
        PermissionResolver.ResolvedPair pair = resolver.resolve(matrixHandler());

        assertThat(pair).as("the handler must resolve to a concrete (resource, operation) pair").isNotNull();
        assertThat(pair.resource()).isEqualTo("WORK_REPORTS");
        assertThat(pair.operation()).isEqualTo("READ");
    }

    @Test
    void controllerRidesTheExistingWorkReportsResourceWithoutDefiningANewOne() {
        // The class-level @PermissionResource names WORK_REPORTS — the FOR-06-01 resource — not a new one.
        PermissionResource resource = MarginMatrixController.class.getAnnotation(PermissionResource.class);
        assertThat(resource).as("the controller must be guarded by a class-level @PermissionResource").isNotNull();
        assertThat(resource.value())
                .as("the matrix rides the existing WORK_REPORTS resource; no new ABAC resource (R6.1)")
                .isEqualTo("WORK_REPORTS");
    }

    // --- No new ABAC resource / no migration (R6.1, design §Overview) --------------------------

    @Test
    void noFor0606LiquibaseChangesetWasAdded() throws Exception {
        Path changelog = changelogPath();
        String xml = Files.readString(changelog);

        // The WORK_REPORTS resource is seeded by FOR-06-01 (changeset 165); this spec adds no changeset.
        assertThat(xml)
                .as("the WORK_REPORTS resource must still be seeded by the FOR-06-01 changeset 165")
                .contains("165-seed-work-reports-resource.xml");
        assertThat(xml)
                .as("FOR-06-06 is a read-only projection — it must add no Liquibase changeset")
                .doesNotContain("margin-matrix")
                .doesNotContain("FOR-06-06");
    }

    @Test
    void noMarginMatrixChangesetFileExists() throws Exception {
        Path changesets = changelogPath().getParent().resolve("changesets");
        assertThat(Files.isDirectory(changesets)).isTrue();
        try (Stream<Path> files = Files.list(changesets)) {
            List<String> matrixChangesets = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.contains("margin-matrix")
                            || name.toLowerCase().contains("for-06-06")
                            || name.toLowerCase().contains("for0606"))
                    .toList();
            assertThat(matrixChangesets)
                    .as("no margin-matrix / FOR-06-06 changeset file may exist (no migration)")
                    .isEmpty();
        }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private HandlerMethod matrixHandler() throws NoSuchMethodException {
        Method method = MarginMatrixController.class.getMethod("getMatrix", Long.class);
        return new HandlerMethod(new MarginMatrixController(null), method);
    }

    /** Resolve {@code database_files/changelog.xml} relative to the backend module working dir. */
    private Path changelogPath() {
        Path fromModule = Paths.get("database_files", "changelog.xml");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        // When tests run from the repo root, the backend module prefixes the path.
        return Paths.get("foremen-backend", "database_files", "changelog.xml");
    }
}
