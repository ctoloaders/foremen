package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.document.CreateDocumentRequest;
import com.foremen.service.document.DeclineRequest;
import com.foremen.service.document.DocumentBodyInput;
import com.foremen.service.document.FormFieldValuesInput;
import com.foremen.service.document.ProviderCallback;
import com.foremen.service.document.RequestSignaturesInput;
import com.foremen.service.document.SignRequest;
import com.foremen.service.document.SignableDocumentTypeInput;
import com.foremen.service.document.CompanyProfileInput;
import com.foremen.service.document.TemplateSaveInput;

/**
 * FOR-05-08 (task 11.4) — ABAC mapping + startup-annotation completeness verification for the five
 * document-signing controllers introduced by this spec.
 *
 * <p>This is a fast, <b>context-free reflection test</b>, following the repository's established
 * {@code *ControllerAbacTest} convention ({@link OfferControllersAbacTest},
 * {@link EstimateMarginsControllerAbacTest}, {@link EstimateMaterialsControllerAbacTest},
 * {@link EstimateMatrixControllerAbacTest}, {@link WorkerTypeControllerAbacTest}). It exercises the
 * <b>real</b> {@link PermissionResolver} — the exact code path the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator}
 * ({@code SmartInitializingSingleton}) run per handler — so it asserts the identical logic the
 * startup validator enforces, without a Spring context or Testcontainers. The validator throws (and
 * fails application startup) iff any in-scope handler classifies anything other than
 * {@link PermissionResolver.Completeness#COMPLETE}; asserting COMPLETE for every handler here is the
 * per-handler check the validator performs at boot.
 *
 * <p>It asserts, as coherent groups, the guarantees task 11.4 mandates (R8.1, R8.6):
 * <ol>
 *   <li><b>Class-level {@code @PermissionResource}.</b> Each guarded controller declares the exact
 *       resource code seeded for it (changesets 145–148): {@code SIGNABLE_DOCUMENTS},
 *       {@code SIGNABLE_DOCUMENT_TYPES}, {@code DOCUMENT_TEMPLATES}, {@code COMPANY_PROFILE} (R8.1,
 *       R8.7).</li>
 *   <li><b>Endpoint → (resource, operation) mapping (R8.2).</b> Every handler resolves through the
 *       real {@link PermissionResolver} to the standard CRUD pair the design mandates — signing
 *       actions map to {@code UPDATE} (no new ABAC operation), reads to {@code READ}, {@code create}
 *       to {@code CREATE}, and destructive actions ({@code void}, media delete) to {@code DELETE}.</li>
 *   <li><b>Startup completeness (R8.6, {@code PermissionAnnotationValidator}).</b> Every handler of
 *       the four guarded controllers classifies {@code COMPLETE} — none half-annotated, none
 *       UNGUARDED — so a loading context proves the validator passed all four.</li>
 *   <li><b>Intentionally-unguarded webhook (R13.1).</b> {@link SignatureCallbackController} carries
 *       none of the three permission annotations (like {@code AuthController}); its handler resolves
 *       to {@code null} (UNGUARDED) yet classifies {@code COMPLETE} (neither-present), so the
 *       validator admits it rather than failing startup on a half-annotated controller.</li>
 * </ol>
 *
 * <p>Confidentiality (R9.6, R13.3) — that no client-reachable document DTO carries a
 * cost/margin/estimate/price field — is asserted by the companion structural reflection test
 * {@link com.foremen.service.document.DocumentDtoConfidentialityStructuralTest}.
 *
 * <p>Validates: Requirements 8.1, 8.6
 */
@DisplayName("FOR-05-08 document-signing controllers — ABAC mapping + startup completeness + unguarded webhook")
@Tag("Feature: FOR-05-08-document-signing, task 11.4: ABAC / startup integration tests")
class DocumentSigningControllersAbacTest {

    private static final String SIGNABLE_DOCUMENTS = "SIGNABLE_DOCUMENTS";
    private static final String SIGNABLE_DOCUMENT_TYPES = "SIGNABLE_DOCUMENT_TYPES";
    private static final String DOCUMENT_TEMPLATES = "DOCUMENT_TEMPLATES";
    private static final String COMPANY_PROFILE = "COMPANY_PROFILE";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final PermissionResolver resolver = new PermissionResolver();

    // Bare controller instances used solely as the bean for HandlerMethod construction; their null
    // collaborators are never dereferenced because only annotations are inspected.
    private final SignableDocumentController documentController =
            new SignableDocumentController(null, null, null);
    private final SignableDocumentTypeController typeController =
            new SignableDocumentTypeController(null);
    private final DocumentTemplateController templateController =
            new DocumentTemplateController(null);
    private final CompanyProfileController companyProfileController =
            new CompanyProfileController(null);
    private final SignatureCallbackController callbackController =
            new SignatureCallbackController(null);

    // ---------------------------------------------------------------------------------------------
    // 1. Class-level @PermissionResource — each guarded controller declares the resource code seeded
    //    for it (changesets 145–148); the webhook carries none (R8.1, R8.7, R13.1).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("class-level @PermissionResource (seeded resource codes)")
    class ClassLevelResource {

        @Test
        @DisplayName("SignableDocumentController carries @PermissionResource(\"SIGNABLE_DOCUMENTS\")")
        void documentControllerResource() {
            assertResourceOf(SignableDocumentController.class, SIGNABLE_DOCUMENTS);
        }

        @Test
        @DisplayName("SignableDocumentTypeController carries @PermissionResource(\"SIGNABLE_DOCUMENT_TYPES\")")
        void typeControllerResource() {
            assertResourceOf(SignableDocumentTypeController.class, SIGNABLE_DOCUMENT_TYPES);
        }

        @Test
        @DisplayName("DocumentTemplateController carries @PermissionResource(\"DOCUMENT_TEMPLATES\")")
        void templateControllerResource() {
            assertResourceOf(DocumentTemplateController.class, DOCUMENT_TEMPLATES);
        }

        @Test
        @DisplayName("CompanyProfileController carries @PermissionResource(\"COMPANY_PROFILE\")")
        void companyProfileControllerResource() {
            assertResourceOf(CompanyProfileController.class, COMPANY_PROFILE);
        }

        @Test
        @DisplayName("SignatureCallbackController carries NO @PermissionResource — intentionally unguarded (R13.1)")
        void callbackControllerHasNoResource() {
            assertThat(SignatureCallbackController.class.getAnnotation(PermissionResource.class))
                    .as("the provider webhook is unauthenticated (like AuthController) and must carry "
                            + "no class-level @PermissionResource")
                    .isNull();
        }

        private void assertResourceOf(Class<?> controller, String expected) {
            PermissionResource annotation = controller.getAnnotation(PermissionResource.class);
            assertThat(annotation)
                    .as("%s must carry class-level @PermissionResource", controller.getSimpleName())
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s class-level resource code (must match the seeded resource)",
                            controller.getSimpleName())
                    .isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Endpoint → (resource, operation) mapping — signing actions are UPDATE (no new operation),
    //    reads READ, create CREATE, destructive actions DELETE (R8.2).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SignableDocumentController endpoint → (SIGNABLE_DOCUMENTS, op) mapping (R8.2)")
    class DocumentEndpointMapping {

        @Test
        @DisplayName("reads (list/read/progress/document/media) → (SIGNABLE_DOCUMENTS, READ)")
        void readsAreRead() {
            assertResolvesTo(docHandler("list", Long.class), SIGNABLE_DOCUMENTS, READ);
            assertResolvesTo(docHandler("read", Long.class), SIGNABLE_DOCUMENTS, READ);
            assertResolvesTo(docHandler("progress", Long.class), SIGNABLE_DOCUMENTS, READ);
            assertResolvesTo(docHandler("document", Long.class), SIGNABLE_DOCUMENTS, READ);
            assertResolvesTo(docHandler("listMedia", Long.class), SIGNABLE_DOCUMENTS, READ);
        }

        @Test
        @DisplayName("create → (SIGNABLE_DOCUMENTS, CREATE)")
        void createIsCreate() {
            assertResolvesTo(docHandler("create", CreateDocumentRequest.class),
                    SIGNABLE_DOCUMENTS, CREATE);
        }

        @Test
        @DisplayName("every mutation / signing action → (SIGNABLE_DOCUMENTS, UPDATE) — no new operation (R8.2)")
        void mutationsAndSigningAreUpdate() {
            assertResolvesTo(docHandler("generate", Long.class), SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("saveBody", Long.class, DocumentBodyInput.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("requestSignatures", Long.class, RequestSignaturesInput.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("fillFormFields", Long.class, FormFieldValuesInput.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("sign", Long.class, MultipartFile.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("signTablet", Long.class, MultipartFile.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("signProvider", Long.class, SignRequest.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("decline", Long.class, DeclineRequest.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
            assertResolvesTo(docHandler("uploadMedia", Long.class, MultipartFile.class),
                    SIGNABLE_DOCUMENTS, UPDATE);
        }

        @Test
        @DisplayName("destructive actions (void, media delete) → (SIGNABLE_DOCUMENTS, DELETE)")
        void destructiveActionsAreDelete() {
            assertResolvesTo(docHandler("voidDocument", Long.class), SIGNABLE_DOCUMENTS, DELETE);
            assertResolvesTo(docHandler("deleteMedia", Long.class, Long.class),
                    SIGNABLE_DOCUMENTS, DELETE);
        }
    }

    @Nested
    @DisplayName("admin controller endpoint → (resource, op) mapping")
    class AdminEndpointMapping {

        @Test
        @DisplayName("SignableDocumentTypeController: list/get READ, create CREATE, update/activate/deactivate UPDATE")
        void typeControllerMapping() {
            assertResolvesTo(typeHandler("list"), SIGNABLE_DOCUMENT_TYPES, READ);
            assertResolvesTo(typeHandler("get", Long.class), SIGNABLE_DOCUMENT_TYPES, READ);
            assertResolvesTo(typeHandler("create", SignableDocumentTypeInput.class),
                    SIGNABLE_DOCUMENT_TYPES, CREATE);
            assertResolvesTo(typeHandler("update", Long.class, SignableDocumentTypeInput.class),
                    SIGNABLE_DOCUMENT_TYPES, UPDATE);
            assertResolvesTo(typeHandler("activate", Long.class), SIGNABLE_DOCUMENT_TYPES, UPDATE);
            assertResolvesTo(typeHandler("deactivate", Long.class), SIGNABLE_DOCUMENT_TYPES, UPDATE);
        }

        @Test
        @DisplayName("DocumentTemplateController: reads READ, create/import CREATE, save/activate/deactivate UPDATE")
        void templateControllerMapping() {
            assertResolvesTo(templateHandler("list"), DOCUMENT_TEMPLATES, READ);
            assertResolvesTo(templateHandler("get", Long.class), DOCUMENT_TEMPLATES, READ);
            assertResolvesTo(templateHandler("listByTypeAndLocale", Long.class, String.class),
                    DOCUMENT_TEMPLATES, READ);
            assertResolvesTo(templateHandler("create", TemplateSaveInput.class),
                    DOCUMENT_TEMPLATES, CREATE);
            assertResolvesTo(
                    templateHandler("importDocx", Long.class, String.class, String.class, MultipartFile.class),
                    DOCUMENT_TEMPLATES, CREATE);
            assertResolvesTo(templateHandler("save", TemplateSaveInput.class),
                    DOCUMENT_TEMPLATES, UPDATE);
            assertResolvesTo(templateHandler("activate", Long.class), DOCUMENT_TEMPLATES, UPDATE);
            assertResolvesTo(templateHandler("deactivate", Long.class), DOCUMENT_TEMPLATES, UPDATE);
            assertResolvesTo(templateHandler("testMerge", Long.class), DOCUMENT_TEMPLATES, READ);
        }

        @Test
        @DisplayName("CompanyProfileController: get READ, save UPDATE (single-row upsert)")
        void companyProfileControllerMapping() {
            assertResolvesTo(companyProfileHandler("get"), COMPANY_PROFILE, READ);
            assertResolvesTo(companyProfileHandler("save", CompanyProfileInput.class),
                    COMPANY_PROFILE, UPDATE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Startup completeness — every handler of the four guarded controllers COMPLETE, so a loading
    //    context proves the PermissionAnnotationValidator classified them COMPLETE at startup.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("startup completeness — every guarded handler COMPLETE (PermissionAnnotationValidator)")
    class StartupCompleteness {

        @Test
        @DisplayName("every SignableDocumentController handler classifies COMPLETE")
        void documentHandlersComplete() {
            assertComplete(docHandler("list", Long.class));
            assertComplete(docHandler("read", Long.class));
            assertComplete(docHandler("progress", Long.class));
            assertComplete(docHandler("document", Long.class));
            assertComplete(docHandler("listMedia", Long.class));
            assertComplete(docHandler("create", CreateDocumentRequest.class));
            assertComplete(docHandler("generate", Long.class));
            assertComplete(docHandler("saveBody", Long.class, DocumentBodyInput.class));
            assertComplete(docHandler("requestSignatures", Long.class, RequestSignaturesInput.class));
            assertComplete(docHandler("fillFormFields", Long.class, FormFieldValuesInput.class));
            assertComplete(docHandler("sign", Long.class, MultipartFile.class));
            assertComplete(docHandler("signTablet", Long.class, MultipartFile.class));
            assertComplete(docHandler("signProvider", Long.class, SignRequest.class));
            assertComplete(docHandler("decline", Long.class, DeclineRequest.class));
            assertComplete(docHandler("uploadMedia", Long.class, MultipartFile.class));
            assertComplete(docHandler("voidDocument", Long.class));
            assertComplete(docHandler("deleteMedia", Long.class, Long.class));
        }

        @Test
        @DisplayName("every SignableDocumentTypeController handler classifies COMPLETE")
        void typeHandlersComplete() {
            assertComplete(typeHandler("list"));
            assertComplete(typeHandler("get", Long.class));
            assertComplete(typeHandler("create", SignableDocumentTypeInput.class));
            assertComplete(typeHandler("update", Long.class, SignableDocumentTypeInput.class));
            assertComplete(typeHandler("activate", Long.class));
            assertComplete(typeHandler("deactivate", Long.class));
        }

        @Test
        @DisplayName("every DocumentTemplateController handler classifies COMPLETE")
        void templateHandlersComplete() {
            assertComplete(templateHandler("list"));
            assertComplete(templateHandler("get", Long.class));
            assertComplete(templateHandler("listByTypeAndLocale", Long.class, String.class));
            assertComplete(templateHandler("create", TemplateSaveInput.class));
            assertComplete(templateHandler("importDocx", Long.class, String.class, String.class,
                    MultipartFile.class));
            assertComplete(templateHandler("save", TemplateSaveInput.class));
            assertComplete(templateHandler("activate", Long.class));
            assertComplete(templateHandler("deactivate", Long.class));
            assertComplete(templateHandler("testMerge", Long.class));
        }

        @Test
        @DisplayName("every CompanyProfileController handler classifies COMPLETE")
        void companyProfileHandlersComplete() {
            assertComplete(companyProfileHandler("get"));
            assertComplete(companyProfileHandler("save", CompanyProfileInput.class));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The intentionally-unguarded webhook (R13.1): no annotations → resolve() returns null
    //    (UNGUARDED) yet classifyCompleteness() returns COMPLETE (neither present), so the startup
    //    validator admits it rather than failing on a half-annotated controller.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SignatureCallbackController — intentionally unguarded webhook (R13.1)")
    class UnguardedWebhook {

        @Test
        @DisplayName("callback handler resolves to null (UNGUARDED) — no (resource, operation) pair")
        void callbackResolvesToNull() {
            PermissionResolver.ResolvedPair pair = resolver.resolve(callbackHandler());
            assertThat(pair)
                    .as("the unauthenticated provider webhook must be UNGUARDED (no ABAC pair) — the "
                            + "caller is a QTSP/Profil Zaufany provider, not a Foremen principal")
                    .isNull();
        }

        @Test
        @DisplayName("callback handler classifies COMPLETE (neither-present) — validator admits it, no startup failure")
        void callbackClassifiesComplete() {
            assertThat(resolver.classifyCompleteness(callbackHandler()))
                    .as("a controller carrying none of the three annotations is deliberately unguarded, "
                            + "classified COMPLETE so PermissionAnnotationValidator does not fail startup")
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
        }
    }

    // --- helpers ---

    private HandlerMethod docHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(documentController, SignableDocumentController.class, methodName, paramTypes);
    }

    private HandlerMethod typeHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(typeController, SignableDocumentTypeController.class, methodName, paramTypes);
    }

    private HandlerMethod templateHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(templateController, DocumentTemplateController.class, methodName, paramTypes);
    }

    private HandlerMethod companyProfileHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(companyProfileController, CompanyProfileController.class, methodName, paramTypes);
    }

    private HandlerMethod callbackHandler() {
        return handlerFor(callbackController, SignatureCallbackController.class, "callback",
                ProviderCallback.class);
    }

    private HandlerMethod handlerFor(
            Object bean, Class<?> controllerType, String methodName, Class<?>... paramTypes) {
        try {
            Method method = controllerType.getMethod(methodName, paramTypes);
            return new HandlerMethod(bean, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    controllerType.getSimpleName() + " is missing expected handler " + methodName, e);
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
