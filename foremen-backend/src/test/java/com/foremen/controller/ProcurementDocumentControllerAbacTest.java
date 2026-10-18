package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;

/**
 * ABAC + startup-annotation verification for {@link ProcurementDocumentController} (FOR-06-03 task
 * 6.1).
 *
 * <p>Asserts, at the annotation/resolution level, the two guarantees the runtime ABAC stack and the
 * startup {@code PermissionAnnotationValidator} depend on (design §5.5, entity-creation-rules step
 * 3):
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping (R6.1, R7.3, R8.1).</b> Each handler resolves
 *       through the real {@link PermissionResolver#resolve(HandlerMethod)} (the exact code path the
 *       request-time {@code PermissionInterceptor} runs) to the pair the design mandates: upload →
 *       {@code PURCHASES}/{@code CREATE}, list / read-one / images → {@code PURCHASES}/{@code READ},
 *       re-process → {@code PURCHASES}/{@code UPDATE}, delete → {@code PURCHASES}/{@code DELETE}.</li>
 *   <li><b>Startup completeness (R7.3, {@code PermissionAnnotationValidator}).</b> Because the
 *       controller does not inherit any {@code @PermissionOperation} from {@code AdminController},
 *       every in-scope handler carries a method-level {@code @RequiresPermission}; each therefore
 *       classifies {@link PermissionResolver.Completeness#COMPLETE} via
 *       {@link PermissionResolver#classifyCompleteness(HandlerMethod)} — the exact per-handler check
 *       the {@code PermissionAnnotationValidator} {@code SmartInitializingSingleton} performs at
 *       startup. No handler is half-annotated ({@code RESOURCE_WITHOUT_OPERATION} /
 *       {@code OPERATION_WITHOUT_RESOURCE}) and none is UNGUARDED, so a loading application context
 *       would not fail on this controller.</li>
 * </ol>
 *
 * <p>This is a fast, context-free reflection test mirroring {@link EstimateMaterialsControllerAbacTest}
 * / {@link EstimateMatrixControllerAbacTest}: it builds a {@link HandlerMethod} directly from a bare
 * controller instance ({@code null} collaborators are never dereferenced — only annotations are
 * read) and exercises the real {@link PermissionResolver}, so it needs neither a Spring context nor
 * Testcontainers while asserting the identical logic the startup validator relies on.
 *
 * <p>Validates: Requirements 6.1, 7.3, 8.1
 */
@DisplayName("ProcurementDocumentController ABAC mapping + startup completeness")
class ProcurementDocumentControllerAbacTest {

    private static final String PURCHASES = "PURCHASES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final ProcurementDocumentController controller =
            new ProcurementDocumentController(null, null);

    // --- class-level guarding: the PURCHASES resource (entity-creation-rules step 3) ---

    @Test
    @DisplayName("controller carries class-level @PermissionResource(\"PURCHASES\")")
    void controllerDeclaresPurchasesResource() {
        PermissionResource annotation =
                ProcurementDocumentController.class.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("ProcurementDocumentController must carry class-level @PermissionResource")
                .isNotNull();
        assertThat(annotation.value())
                .as("class-level resource code must be PURCHASES (design §5.5)")
                .isEqualTo(PURCHASES);
    }

    // --- endpoint → (resource, operation) mapping (R6.1, R7.3, R8.1) ---

    @Test
    @DisplayName("POST / (upload) → (PURCHASES, CREATE)")
    void uploadIsPurchasesCreate() {
        assertResolvesTo(handlerFor("upload", Long.class, MultipartFile.class), PURCHASES, CREATE);
    }

    @Test
    @DisplayName("GET /?projectId= (list) → (PURCHASES, READ)")
    void listIsPurchasesRead() {
        assertResolvesTo(handlerFor("list", Long.class, Pageable.class), PURCHASES, READ);
    }

    @Test
    @DisplayName("GET /{id} (read one) → (PURCHASES, READ)")
    void findByIdIsPurchasesRead() {
        assertResolvesTo(handlerFor("findById", Long.class), PURCHASES, READ);
    }

    @Test
    @DisplayName("POST /{id}/reprocess → (PURCHASES, UPDATE)")
    void reprocessIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("reprocess", Long.class), PURCHASES, UPDATE);
    }

    @Test
    @DisplayName("DELETE /{id} → (PURCHASES, DELETE)")
    void deleteIsPurchasesDelete() {
        assertResolvesTo(handlerFor("delete", Long.class), PURCHASES, DELETE);
    }

    @Test
    @DisplayName("GET /{id}/images → (PURCHASES, READ)")
    void imagesIsPurchasesRead() {
        assertResolvesTo(handlerFor("images", Long.class), PURCHASES, READ);
    }

    // --- startup completeness — every handler COMPLETE (PermissionAnnotationValidator) ---

    @Test
    @DisplayName("every handler classifies COMPLETE (no half-annotation, none UNGUARDED)")
    void everyHandlerComplete() {
        assertComplete(handlerFor("upload", Long.class, MultipartFile.class));
        assertComplete(handlerFor("list", Long.class, Pageable.class));
        assertComplete(handlerFor("findById", Long.class));
        assertComplete(handlerFor("reprocess", Long.class));
        assertComplete(handlerFor("delete", Long.class));
        assertComplete(handlerFor("images", Long.class));
    }

    // --- helpers ---

    private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) {
        try {
            Method method = ProcurementDocumentController.class.getMethod(methodName, paramTypes);
            return new HandlerMethod(controller, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "ProcurementDocumentController is missing expected handler " + methodName, e);
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
