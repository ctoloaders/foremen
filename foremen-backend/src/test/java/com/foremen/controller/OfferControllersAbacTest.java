package com.foremen.controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.offer.ClientOfferReadModel;
import com.foremen.service.offer.OfferVisibilityResolver;

/**
 * FOR-05-07 task 9.4 — ABAC + confidentiality + service-lock verification for the four Offer-stage
 * controllers ({@link OfferController}, {@link OfferNegotiationController}, {@link ClientOfferController},
 * {@link NotificationController}).
 *
 * <p>This is a fast, context-free reflection test, following the repository's established
 * {@code *ControllerAbacTest} convention ({@link EstimateMatrixControllerAbacTest},
 * {@link EstimateMaterialsControllerAbacTest}, {@link EstimateMarginsControllerAbacTest},
 * {@link WorkerTypeControllerAbacTest}). It exercises the <b>real</b> collaborators the runtime ABAC
 * stack relies on — the {@link PermissionResolver} (the exact code path the request-time
 * {@code PermissionInterceptor} and the startup {@code PermissionAnnotationValidator} run), the real
 * {@link OfferVisibilityResolver} (the client-visibility gate), and the real {@link DraftGateGuard}
 * (the estimate service-lock) — so it asserts the identical logic those components enforce, without
 * a Spring context or Testcontainers. The whole-app boot facet (a context that loads proves
 * {@code PermissionAnnotationValidator} classified every controller COMPLETE) is covered by the
 * companion {@link OfferControllersStartupSmokeTest}, mirroring how
 * {@code EstimateControllersStartupIntegrationTest} complements the estimate ABAC tests.
 *
 * <p>It asserts, as coherent groups, the guarantees task 9.4 mandates:
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping incl. {@code (OFFERS, APPROVE)}.</b> Every
 *       handler of all four controllers resolves through the real {@link PermissionResolver} to the
 *       pair the design mandates; the CLIENT approve/reject sub-actions resolve to the first-class
 *       {@code (OFFERS, APPROVE)} grant (R5.4, R5.6).</li>
 *   <li><b>Startup completeness (PermissionAnnotationValidator).</b> Every handler classifies
 *       {@link PermissionResolver.Completeness#COMPLETE} — none half-annotated, none UNGUARDED — so a
 *       loading context proves the validator passed all four controllers.</li>
 *   <li><b>Confidentiality (Property 21 as a structural endpoint example, R15).</b> The
 *       {@link ClientOfferReadModel} type graph — the ONLY payload a CLIENT is ever served — contains
 *       no field whose name denotes a cost, margin, worker-rate, or estimate-internal unit price.</li>
 *   <li><b>Visibility (Property 9 as an example, R5.9).</b> The real {@link OfferVisibilityResolver}
 *       denies a CLIENT reaching a {@code DRAFT}-visibility offer with {@code 404
 *       error.entity.not.found} (indistinguishable from missing), and admits an {@code ON_APPROVAL} /
 *       {@code APPROVED} offer.</li>
 *   <li><b>Service-lock (Property 22 as an example, R20).</b> The real {@link DraftGateGuard} rejects
 *       an estimate/offer write on a project whose estimate is past {@code DRAFT} (i.e. the project is
 *       {@code ACTIVE}) with {@code 409 error.estimate.locked}.</li>
 *   <li><b>Tightened role model (R16).</b> The seed changesets grant {@code OFFERS} only to
 *       ADMIN/MANAGER/CLIENT (never FOREMAN/WORKER/FINANCIER), the CLIENT holds {@code APPROVE}, the
 *       {@code 135} retighten removes the FOREMAN/WORKER/FINANCIER ESTIMATE/OFFERS grants, and
 *       {@code NOTIFICATIONS} grants READ/UPDATE/DELETE to every role and CREATE to none.</li>
 * </ol>
 *
 * <p>Validates: Requirements 5.9, 5.11, 15.4, 16.1, 16.2, 16.3, 20.2, 20.5
 */
@DisplayName("FOR-05-07 Offer controllers — ABAC mapping + startup completeness + confidentiality + visibility + service-lock")
@Tag("Feature: FOR-05-07-offer-approval, task 9.4: ABAC + confidentiality + service-lock integration tests")
class OfferControllersAbacTest {

    private static final String OFFERS = "OFFERS";
    private static final String NOTIFICATIONS = "NOTIFICATIONS";
    private static final String CREATE = "CREATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String APPROVE = "APPROVE";

    private final PermissionResolver resolver = new PermissionResolver();

    // Bare controller instances used solely as the bean for HandlerMethod construction; their null
    // collaborators are never dereferenced because only annotations are inspected.
    private final OfferController offerController =
            new OfferController(null, null, null);
    private final OfferNegotiationController negotiationController =
            new OfferNegotiationController(null, null, null);
    private final ClientOfferController clientOfferController =
            new ClientOfferController(null);
    private final NotificationController notificationController =
            new NotificationController(null);

    // ---------------------------------------------------------------------------------------------
    // 1. Class-level @PermissionResource — OFFERS is shared across the three offer controllers,
    //    NOTIFICATIONS on the bell controller (R16.3, R5.1, R13.13).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("class-level @PermissionResource")
    class ClassLevelResource {

        @Test
        @DisplayName("OfferController carries @PermissionResource(\"OFFERS\")")
        void offerControllerOffers() {
            assertResourceOf(OfferController.class, OFFERS);
        }

        @Test
        @DisplayName("OfferNegotiationController carries @PermissionResource(\"OFFERS\")")
        void negotiationControllerOffers() {
            assertResourceOf(OfferNegotiationController.class, OFFERS);
        }

        @Test
        @DisplayName("ClientOfferController carries @PermissionResource(\"OFFERS\")")
        void clientOfferControllerOffers() {
            assertResourceOf(ClientOfferController.class, OFFERS);
        }

        @Test
        @DisplayName("NotificationController carries @PermissionResource(\"NOTIFICATIONS\")")
        void notificationControllerNotifications() {
            assertResourceOf(NotificationController.class, NOTIFICATIONS);
        }

        private void assertResourceOf(Class<?> controller, String expected) {
            PermissionResource annotation = controller.getAnnotation(PermissionResource.class);
            assertThat(annotation)
                    .as("%s must carry class-level @PermissionResource", controller.getSimpleName())
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s class-level resource code", controller.getSimpleName())
                    .isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Endpoint → (resource, operation) mapping incl. (OFFERS, APPROVE) for the client decision
    //    (R5.4, R5.6, R13.13).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("endpoint → (resource, operation) mapping")
    class EndpointMapping {

        @Test
        @DisplayName("POST /project/{id}/prepare → (OFFERS, CREATE)")
        void prepareIsOffersCreate() {
            assertResolvesTo(offerHandler("prepare", Long.class), OFFERS, CREATE);
        }

        @Test
        @DisplayName("POST /{offerId}/select-package → (OFFERS, UPDATE)")
        void selectPackageIsOffersUpdate() {
            assertResolvesTo(
                    offerHandler("selectPackage", Long.class, OfferController.SelectPackageRequest.class),
                    OFFERS, UPDATE);
        }

        @Test
        @DisplayName("POST /{offerId}/send → (OFFERS, UPDATE)")
        void sendIsOffersUpdate() {
            assertResolvesTo(offerHandler("send", Long.class), OFFERS, UPDATE);
        }

        @Test
        @DisplayName("POST /{offerId}/withdraw → (OFFERS, UPDATE)")
        void withdrawIsOffersUpdate() {
            assertResolvesTo(offerHandler("withdraw", Long.class), OFFERS, UPDATE);
        }

        @Test
        @DisplayName("POST /{offerId}/approve → (OFFERS, APPROVE) — the client decision (R5.6)")
        void approveIsOffersApprove() {
            assertResolvesTo(offerHandler("approve", Long.class), OFFERS, APPROVE);
        }

        @Test
        @DisplayName("POST /{offerId}/reject → (OFFERS, APPROVE) — the client decision (R5.6)")
        void rejectIsOffersApprove() {
            assertResolvesTo(offerHandler("reject", Long.class), OFFERS, APPROVE);
        }

        @Test
        @DisplayName("GET /{offerId} → (OFFERS, READ)")
        void getOfferIsOffersRead() {
            assertResolvesTo(offerHandler("getOffer", Long.class), OFFERS, READ);
        }

        @Test
        @DisplayName("negotiation round mutators are each (OFFERS, UPDATE)")
        void negotiationRoundsAreOffersUpdate() {
            assertResolvesTo(
                    negotiationHandler("openDiscountRequest", Long.class,
                            OfferNegotiationController.DiscountRequest.class),
                    OFFERS, UPDATE);
            assertResolvesTo(
                    negotiationHandler("propose", Long.class,
                            OfferNegotiationController.ProposeRequest.class),
                    OFFERS, UPDATE);
            assertResolvesTo(
                    negotiationHandler("reject", Long.class,
                            OfferNegotiationController.RejectRequest.class),
                    OFFERS, UPDATE);
            assertResolvesTo(negotiationHandler("accept", Long.class), OFFERS, UPDATE);
            assertResolvesTo(negotiationHandler("decline", Long.class), OFFERS, UPDATE);
        }

        @Test
        @DisplayName("POST /{offerId}/finishing/{lineId}/choose-concrete → (OFFERS, UPDATE)")
        void chooseConcreteIsOffersUpdate() {
            assertResolvesTo(
                    clientOfferHandler("chooseConcrete", Long.class, Long.class,
                            ClientOfferController.ChooseConcreteRequest.class),
                    OFFERS, UPDATE);
        }

        @Test
        @DisplayName("notification list / unread-count → (NOTIFICATIONS, READ)")
        void notificationReadsAreNotificationsRead() {
            assertResolvesTo(notificationHandler("list"), NOTIFICATIONS, READ);
            assertResolvesTo(notificationHandler("unreadCount"), NOTIFICATIONS, READ);
        }

        @Test
        @DisplayName("notification toggle-read → (NOTIFICATIONS, UPDATE)")
        void notificationToggleIsNotificationsUpdate() {
            assertResolvesTo(notificationHandler("toggleRead", Long.class), NOTIFICATIONS, UPDATE);
        }

        @Test
        @DisplayName("notification delete → (NOTIFICATIONS, DELETE)")
        void notificationDeleteIsNotificationsDelete() {
            assertResolvesTo(notificationHandler("delete", Long.class), NOTIFICATIONS, DELETE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Startup completeness — every handler COMPLETE, so a loading context proves the
    //    PermissionAnnotationValidator classified all four controllers COMPLETE.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("startup completeness — every handler COMPLETE (PermissionAnnotationValidator)")
    class StartupCompleteness {

        @Test
        @DisplayName("every OfferController handler classifies COMPLETE")
        void offerHandlersComplete() {
            assertComplete(offerHandler("prepare", Long.class));
            assertComplete(offerHandler("selectPackage", Long.class,
                    OfferController.SelectPackageRequest.class));
            assertComplete(offerHandler("send", Long.class));
            assertComplete(offerHandler("withdraw", Long.class));
            assertComplete(offerHandler("approve", Long.class));
            assertComplete(offerHandler("reject", Long.class));
            assertComplete(offerHandler("getOffer", Long.class));
        }

        @Test
        @DisplayName("every OfferNegotiationController handler classifies COMPLETE")
        void negotiationHandlersComplete() {
            assertComplete(negotiationHandler("openDiscountRequest", Long.class,
                    OfferNegotiationController.DiscountRequest.class));
            assertComplete(negotiationHandler("propose", Long.class,
                    OfferNegotiationController.ProposeRequest.class));
            assertComplete(negotiationHandler("reject", Long.class,
                    OfferNegotiationController.RejectRequest.class));
            assertComplete(negotiationHandler("accept", Long.class));
            assertComplete(negotiationHandler("decline", Long.class));
        }

        @Test
        @DisplayName("ClientOfferController handler classifies COMPLETE")
        void clientOfferHandlerComplete() {
            assertComplete(clientOfferHandler("chooseConcrete", Long.class, Long.class,
                    ClientOfferController.ChooseConcreteRequest.class));
        }

        @Test
        @DisplayName("every NotificationController handler classifies COMPLETE")
        void notificationHandlersComplete() {
            assertComplete(notificationHandler("list"));
            assertComplete(notificationHandler("unreadCount"));
            assertComplete(notificationHandler("toggleRead", Long.class));
            assertComplete(notificationHandler("delete", Long.class));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Confidentiality (Property 21 as a structural endpoint example, R15): the ClientOfferReadModel
    //    type graph — the ONLY payload served to a CLIENT — carries no cost/margin/worker-rate/
    //    estimate-unit-price field name.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("confidentiality — the client read model graph has no cost/margin/estimate-price field (R15)")
    class Confidentiality {

        /** Forbidden field-name substrings (lower-cased) denoting a confidential figure. */
        private final List<String> forbidden = List.of(
                "cost", "margin", "markup", "workerrate", "worker_rate", "unitprice", "unit_price",
                "sebestoim", "estimateprice", "estimate_price", "internalprice");

        @Test
        @DisplayName("no field in the ClientOfferReadModel graph denotes a cost/margin/worker-rate/estimate-unit-price")
        void clientReadModelGraphHasNoConfidentialField() {
            Set<Class<?>> visited = new HashSet<>();
            Deque<Class<?>> queue = new ArrayDeque<>();
            queue.add(ClientOfferReadModel.class);

            while (!queue.isEmpty()) {
                Class<?> type = queue.poll();
                if (!visited.add(type) || !type.isRecord()) {
                    continue;
                }
                for (RecordComponent component : type.getRecordComponents()) {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    for (String bad : forbidden) {
                        assertThat(name)
                                .as("client read model %s.%s must not denote a confidential figure "
                                                + "(forbidden substring '%s') — the CLIENT is served ONLY this "
                                                + "graph and it must carry no cost/margin/worker-rate/"
                                                + "estimate-unit-price field (Property 21, R15)",
                                        type.getSimpleName(), component.getName(), bad)
                                .doesNotContain(bad);
                    }
                    // The referenced estimate must appear ONLY as an opaque id, never as an embedded
                    // estimate DTO (R19.3) — enqueue nested record types to scan the whole graph.
                    enqueueComponentTypes(component, queue);
                }
            }
        }

        private void enqueueComponentTypes(RecordComponent component, Deque<Class<?>> queue) {
            Class<?> raw = component.getType();
            if (raw.isRecord()) {
                queue.add(raw);
            }
            // Unwrap List<X> / Collection<X> generic element types (e.g. List<PerLineOfferPrice>).
            java.lang.reflect.Type generic = component.getGenericType();
            if (generic instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> argClass && argClass.isRecord()) {
                        queue.add(argClass);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Visibility (Property 9 as an example, R5.9): a DRAFT-visibility offer denies CLIENT access
    //    as 404; an ON_APPROVAL / APPROVED offer is admitted. Uses the REAL OfferVisibilityResolver
    //    the OfferController wires on the CLIENT read path.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("client visibility gate (Property 9 example, R5.9)")
    class ClientVisibility {

        private final OfferVisibilityResolver visibilityResolver = new OfferVisibilityResolver();

        @Test
        @DisplayName("a DRAFT-visibility (not-yet-sent) offer denies CLIENT access with 404 error.entity.not.found")
        void draftVisibilityDeniesClientAs404() {
            assertThatThrownBy(() -> visibilityResolver.assertClientVisible(OfferStatus.DRAFT, 4242L))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus())
                                .as("a DRAFT-visibility offer must be indistinguishable from missing")
                                .isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
        }

        @Test
        @DisplayName("an ON_APPROVAL offer (SENT/CHANGES_REQUESTED/COUNTERED) is visible to the CLIENT")
        void onApprovalIsClientVisible() {
            for (OfferStatus visible : List.of(
                    OfferStatus.SENT, OfferStatus.CHANGES_REQUESTED, OfferStatus.COUNTERED,
                    OfferStatus.APPROVED)) {
                assertThatCode(() -> visibilityResolver.assertClientVisible(visible, 7L))
                        .as("a %s offer is client-visible (ON_APPROVAL/APPROVED)", visible)
                        .doesNotThrowAnyException();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Service-lock (Property 22 as an example, R20): an ACTIVE project (estimate past DRAFT)
    //    rejects an estimate/offer package/finishing write via the REAL DraftGateGuard with
    //    409 error.estimate.locked. This is the exact guard OfferService.selectPackage /
    //    chooseFinishingConcrete delegate to.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("estimate service-lock (Property 22 example, R20)")
    class ServiceLock {

        private final DraftGateGuard draftGateGuard = new DraftGateGuard();

        @Test
        @DisplayName("a non-DRAFT estimate (project ACTIVE) rejects an offer/estimate write with 409 error.estimate.locked")
        void nonDraftEstimateRejectsWriteAs409() {
            for (EstimateStatus locked : List.of(
                    EstimateStatus.PRICED, EstimateStatus.APPROVED, EstimateStatus.SIGNED)) {
                EstimateEntity estimate = new EstimateEntity();
                estimate.setStatus(locked);
                assertThatThrownBy(() -> draftGateGuard.assertDraft(estimate))
                        .as("a %s (past-DRAFT) estimate is service-locked", locked)
                        .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ex.getMessageCode()).isEqualTo("error.estimate.locked");
                        });
            }
        }

        @Test
        @DisplayName("a DRAFT estimate admits the write (the gate is orthogonal to ABAC)")
        void draftEstimateAdmitsWrite() {
            EstimateEntity draft = new EstimateEntity();
            draft.setStatus(EstimateStatus.DRAFT);
            assertThatCode(() -> draftGateGuard.assertDraft(draft)).doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Tightened role model (R16) asserted against the seed changesets: OFFERS granted only to
    //    ADMIN/MANAGER/CLIENT with the CLIENT holding APPROVE; FOREMAN/WORKER/FINANCIER never granted
    //    OFFERS or ESTIMATE (retighten 135); NOTIFICATIONS READ/UPDATE/DELETE to every role, no CREATE.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("tightened role model — OFFERS/NOTIFICATIONS seed grants (R16.1, R16.2, R16.3)")
    class TightenedRoleModel {

        @Test
        @DisplayName("133 grants OFFERS to CLIENT with the first-class APPROVE operation (R16.3, R5.7)")
        void offersGrantsClientApprove() throws IOException {
            String changeset = readChangeset("133-seed-offers-resource.xml").toLowerCase(Locale.ROOT);
            // The custom APPROVE operation is seeded (003 only defines CREATE/READ/UPDATE/DELETE).
            assertThat(changeset)
                    .as("133 must seed the custom APPROVE operation")
                    .contains("'approve'");
            // CLIENT receives READ + APPROVE on OFFERS.
            assertThat(changeset)
                    .as("133 must grant CLIENT READ + APPROVE on OFFERS")
                    .contains("r.code = 'client'")
                    .contains("'read', 'approve'");
            // Grants confined to ADMIN/MANAGER/CLIENT.
            assertThat(changeset)
                    .as("OFFERS role_resources rows confined to ADMIN/MANAGER/CLIENT")
                    .contains("r.code in ('admin', 'manager', 'client')");
        }

        @Test
        @DisplayName("133 grants OFFERS to NO FOREMAN/WORKER/FINANCIER role (R16.3)")
        void offersDeniesExecutorReadRoles() throws IOException {
            // Strip the XML explanatory comments (which mention the denied roles in prose) so the
            // assertion is about the executable SQL body, not the documentation.
            String sqlBody = stripXmlComments(readChangeset("133-seed-offers-resource.xml"))
                    .toLowerCase(Locale.ROOT);
            for (String deniedRole : List.of("foreman", "worker", "financier")) {
                assertThat(sqlBody)
                        .as("133 must issue NO OFFERS grant SQL referencing %s (tightened model, R16.3)",
                                deniedRole)
                        .doesNotContain(deniedRole);
            }
        }

        @Test
        @DisplayName("135 retightens ESTIMATE/OFFERS by archiving+deleting FOREMAN/WORKER/FINANCIER grants (R16.1, R16.2)")
        void retightenRemovesExecutorEstimateGrants() throws IOException {
            String changeset =
                    readChangeset("135-retighten-estimate-and-offers-grants.xml").toLowerCase(Locale.ROOT);
            assertThat(changeset)
                    .as("135 targets the FOREMAN/WORKER/FINANCIER roles on ESTIMATE and OFFERS")
                    .contains("'foreman', 'worker', 'financier'")
                    .contains("'estimate', 'offers'");
            // Archive-before-delete convention: both an archive INSERT and a DELETE must be present.
            assertThat(changeset)
                    .as("135 must archive (snapshot) the removed grants before deleting them")
                    .contains("insert into role_resource_operations_archive")
                    .contains("insert into role_resources_archive");
            assertThat(changeset)
                    .as("135 must delete the tightened role_resource_operations + role_resources rows")
                    .contains("delete from role_resource_operations")
                    .contains("delete from role_resources");
        }

        @Test
        @DisplayName("134 grants NOTIFICATIONS READ/UPDATE/DELETE to every role and NO CREATE (R13.13)")
        void notificationsAllRolesNoCreate() throws IOException {
            String changeset =
                    readChangeset("134-seed-notifications-resource.xml").toLowerCase(Locale.ROOT);
            assertThat(changeset)
                    .as("134 grants NOTIFICATIONS to every role")
                    .contains("'admin', 'manager', 'foreman', 'worker', 'financier', 'client'");
            assertThat(changeset)
                    .as("134 grants only READ/UPDATE/DELETE on NOTIFICATIONS")
                    .contains("'read', 'update', 'delete'");
            assertThat(changeset)
                    .as("134 must NOT grant CREATE on NOTIFICATIONS (system-emitted only, R13.13)")
                    .doesNotContain("'create'");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private HandlerMethod offerHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(OfferController.class, offerController, methodName, paramTypes);
    }

    private HandlerMethod negotiationHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(OfferNegotiationController.class, negotiationController, methodName, paramTypes);
    }

    private HandlerMethod clientOfferHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(ClientOfferController.class, clientOfferController, methodName, paramTypes);
    }

    private HandlerMethod notificationHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(NotificationController.class, notificationController, methodName, paramTypes);
    }

    private HandlerMethod handlerFor(Class<?> controllerType, Object bean, String methodName,
            Class<?>... paramTypes) {
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

    /**
     * Reads a changeset XML from the source tree, resolving the path from either the module root or
     * the repo root so the test is independent of the working directory Gradle runs it from.
     */
    private String readChangeset(String fileName) throws IOException {
        Path fromModuleRoot = Paths.get("database_files", "changesets", fileName);
        Path fromRepoRoot = Paths.get("foremen-backend", "database_files", "changesets", fileName);
        Path changeset = Files.exists(fromModuleRoot) ? fromModuleRoot : fromRepoRoot;
        assertThat(Files.exists(changeset))
                .as("changeset must exist at %s", changeset.toAbsolutePath())
                .isTrue();
        return Files.readString(changeset);
    }

    /** Removes XML {@code <!-- ... -->} comment blocks so assertions target the executable SQL body. */
    private String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }
}
