package com.foremen.service.procurement.matrix;

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
import com.foremen.controller.MaterialsMatrixController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-05 (design §B3, §Testing — startup smoke; task 6.2) — startup-annotation smoke for
 * {@link MaterialsMatrixController} plus the no-new-ABAC-resource / no-migration assertions.
 *
 * <p>Rather than boot a full Spring context, this drives the real
 * {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact routine the startup
 * {@code PermissionAnnotationValidator} runs over every handler — against the controller's real
 * {@code getMatrix} handler. Because the handler carries a method-level
 * {@link RequiresPermission @RequiresPermission(PURCHASES, READ)}, the resolver must classify it
 * {@code COMPLETE}, so the controller can never be the cause of a {@code PermissionAnnotationValidator}
 * startup failure (R6.2) — no half-annotation degrades it into an unguarded endpoint.
 *
 * <p>It also pins the spec's deliberate schema-free, resource-free shape (R6.1): the controller rides
 * the existing {@code PURCHASES} resource (seeded by FOR-06-03, changeset 169) — <b>no new ABAC
 * resource</b> — and <b>no Liquibase changeset</b> was added for this spec (the changelog has no
 * {@code materials-matrix} / FOR-06-05 include).
 */
class MaterialsMatrixControllerSmokeTest {

    private final PermissionResolver resolver = new PermissionResolver();

    // --- Startup-annotation smoke (R6.2) -------------------------------------------------------

    @Test
    void getMatrixHandlerIsClassifiedCompleteByTheStartupValidator() throws NoSuchMethodException {
        HandlerMethod handler = matrixHandler();

        // The exact classification the SmartInitializingSingleton PermissionAnnotationValidator runs:
        // a @RequiresPermission handler is always COMPLETE, so startup never fails on this controller.
        assertThat(resolver.classifyCompleteness(handler))
                .as("MaterialsMatrixController.getMatrix must be a COMPLETE permission declaration (R6.2)")
                .isEqualTo(PermissionResolver.Completeness.COMPLETE);
    }

    @Test
    void getMatrixResolvesToPurchasesRead() throws NoSuchMethodException {
        PermissionResolver.ResolvedPair pair = resolver.resolve(matrixHandler());

        assertThat(pair).as("the handler must resolve to a concrete (resource, operation) pair").isNotNull();
        assertThat(pair.resource()).isEqualTo("PURCHASES");
        assertThat(pair.operation()).isEqualTo("READ");
    }

    @Test
    void controllerRidesTheExistingPurchasesResourceWithoutDefiningANewOne() {
        // The class-level @PermissionResource names PURCHASES — the FOR-06-03 resource — not a new one.
        PermissionResource resource = MaterialsMatrixController.class.getAnnotation(PermissionResource.class);
        assertThat(resource).as("the controller must be guarded by a class-level @PermissionResource").isNotNull();
        assertThat(resource.value())
                .as("the matrix rides the existing PURCHASES resource; no new ABAC resource (R6.1)")
                .isEqualTo("PURCHASES");
    }

    // --- No new ABAC resource / no migration (R6.1, design §Overview) --------------------------

    @Test
    void noFor0605LiquibaseChangesetWasAdded() throws Exception {
        Path changelog = changelogPath();
        String xml = Files.readString(changelog);

        // The PURCHASES resource is seeded by FOR-06-03 (changeset 169); this spec adds no changeset.
        assertThat(xml)
                .as("the PURCHASES resource must still be seeded by the FOR-06-03 changeset 169")
                .contains("169-seed-purchases-resource.xml");
        assertThat(xml)
                .as("FOR-06-05 is a read-only projection — it must add no Liquibase changeset")
                .doesNotContain("materials-matrix")
                .doesNotContain("FOR-06-05");
    }

    @Test
    void noMaterialsMatrixChangesetFileExists() throws Exception {
        Path changesets = changelogPath().getParent().resolve("changesets");
        assertThat(Files.isDirectory(changesets)).isTrue();
        try (Stream<Path> files = Files.list(changesets)) {
            List<String> matrixChangesets = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.contains("materials-matrix")
                            || name.toLowerCase().contains("for-06-05")
                            || name.toLowerCase().contains("for0605"))
                    .toList();
            assertThat(matrixChangesets)
                    .as("no materials-matrix / FOR-06-05 changeset file may exist (no migration)")
                    .isEmpty();
        }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private HandlerMethod matrixHandler() throws NoSuchMethodException {
        Method method = MaterialsMatrixController.class.getMethod("getMatrix", Long.class);
        return new HandlerMethod(new MaterialsMatrixController(null), method);
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
