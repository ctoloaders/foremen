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
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.AddLineRequest;
import com.foremen.service.execution.amendment.SendForSigningOptions;

/**
 * FOR-06-08 (Requirements 1.5, 3.7, 9.1, 9.4, 10.3; task 6.1) + FOR-06-09 (Requirements 8.1, 8.2,
 * 8.4; task 6.2) — ABAC mapping + startup-completeness verification for {@link AmendmentController}.
 *
 * <p>Drives the <b>real</b> {@link PermissionResolver} — the exact code path both the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator} run — over the
 * controller's handlers, asserting the guarantees the design and {@code entity-creation-rules}
 * step 3 mandate:
 *
 * <ol>
 *   <li><b>Class-level resource (R9.1).</b> The controller carries
 *       {@code @PermissionResource("AMENDMENTS")} — the exact code task 8.1 seeds.</li>
 *   <li><b>Bespoke-handler mapping (R1.5, R3.7, R10.3; FOR-06-09 R8.1, R8.2).</b> Each bespoke
 *       handler resolves to the design's {@code (resource, operation)} pair: {@code GET /open} →
 *       {@code (AMENDMENTS, READ)}; {@code POST /{id}/lines} → {@code (AMENDMENTS, UPDATE)};
 *       {@code DELETE /{id}/lines/{lineId}} and the overridden {@code DELETE /{id}} →
 *       {@code (AMENDMENTS, DELETE)}; and the FOR-06-09 {@code POST /{id}/send-for-signing} →
 *       {@code (AMENDMENTS, UPDATE)}, so a role lacking the {@code AMENDMENTS} UPDATE grant is
 *       rejected (the {@code PermissionInterceptor} denies with {@code 403}) rather than the send
 *       action being UNGUARDED (R8.1, R8.2).</li>
 *   <li><b>Startup completeness (R9.4; FOR-06-09 R8.4).</b> Every handler — the bespoke ones
 *       (including the new send-for-signing handler) and a representative inherited CRUD
 *       {@code default} (the generic {@code create}, class-resource +
 *       method-{@code @PermissionOperation}) — classifies {@link PermissionResolver.Completeness#COMPLETE}
 *       via {@link PermissionResolver#classifyCompleteness(HandlerMethod)}, the exact per-handler
 *       check the {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs.
 *       No handler is half-annotated ({@code RESOURCE_WITHOUT_OPERATION} /
 *       {@code OPERATION_WITHOUT_RESOURCE}), so a running app can never fail startup on this
 *       controller.</li>
 *   <li><b>No new ABAC resource introduced by FOR-06-09 (R8.1, R8.4).</b> FOR-06-09 is a pure
 *       reflection/application layer that rides the existing {@code AMENDMENTS} resource (seeded by
 *       FOR-06-08 changeset 179) and the FOR-05-08 {@code SIGNABLE_DOCUMENTS} resource — it adds no
 *       Liquibase changeset at all. Asserted directly against the changeset tree: the changelog head
 *       stays at <b>179</b> and no FOR-06-09 {@code *-seed-*-resource.xml} changeset (numbered above
 *       179) was added.</li>
 * </ol>
 *
 * <p>A fast, context-free reflection test (mirrors {@link EstimateMaterialsControllerAbacTest}): it
 * builds a {@link HandlerMethod} from a bare controller instance whose {@code null} collaborators are
 * never dereferenced (only annotations are read), so it needs neither a Spring context nor
 * Testcontainers while asserting the identical logic the startup validator relies on. The "no new
 * resource" guarantee is asserted directly against the changeset tree.
 *
 * <p>Validates: Requirements 1.5, 3.7, 9.1, 9.4, 10.3 (FOR-06-08); 8.1, 8.2, 8.4 (FOR-06-09)
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
    private final AmendmentController controller = new AmendmentController(null, null, null, null);

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
    @DisplayName("FOR-06-09 POST /{id}/send-for-signing → (AMENDMENTS, UPDATE) — 403 without the grant (R8.1, R8.2)")
    void sendForSigningIsAmendmentsUpdate() {
        assertResolvesTo(
                handlerFor("sendForSigning", Long.class, SendForSigningOptions.class), AMENDMENTS, UPDATE);
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
        // FOR-06-09: the new send-for-signing handler is fully annotated (R8.4) — the
        // PermissionAnnotationValidator would otherwise fail startup on it.
        assertComplete(handlerFor("sendForSigning", Long.class, SendForSigningOptions.class));
        // A representative inherited CRUD default (class @PermissionResource + method
        // @PermissionOperation) — proves the inherited handlers are complete too.
        assertComplete(handlerFor("create", Object.class));
    }

    // --- FOR-06-09 adds no new ABAC resource: changelog head stays at 179 (R8.1, R8.4) ----------

    @Test
    @DisplayName("FOR-06-09 introduces NO new resources row — changelog head stays at 179, no new *-resource.xml (R8.1, R8.4)")
    void forThisSpecIntroducesNoNewResourcesRow() throws IOException {
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

        // FOR-06-08's 179-seed-amendments-resource.xml is the last changeset; FOR-06-09 adds none, so
        // the changelog head must still be 179. A head above 179 means a new changeset (potentially a
        // resource seed) was introduced, violating the "no new resource / no migration" contract.
        assertThat(head)
                .as("FOR-06-09 adds no Liquibase changeset — the changelog head must stay at 179 "
                        + "(found %s); the feature rides the existing AMENDMENTS + SIGNABLE_DOCUMENTS "
                        + "resources (R8.1, R8.4)", head)
                .isEqualTo(179);

        // Specifically: no resource-seed changeset numbered above 179 (what a new ABAC resource would
        // look like, e.g. an NNN-seed-<entity>-resource.xml per entity-creation-rules step 1).
        List<String> resourceSeedsAbove179 = numberedChangesets.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("-resource.xml"))
                .filter(name -> Integer.parseInt(name.substring(0, name.indexOf('-'))) > 179)
                .collect(Collectors.toList());
        assertThat(resourceSeedsAbove179)
                .as("FOR-06-09 must seed NO new resources row — found resource-seed changeset(s) above "
                        + "179: %s (R8.1, R8.4)", resourceSeedsAbove179)
                .isEmpty();
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
}
