package com.foremen.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.payment.dto.AddTrancheInput;
import com.foremen.service.payment.dto.CreateScheduleRequest;
import com.foremen.service.payment.dto.GenerateRequest;
import com.foremen.service.payment.dto.InitiatePayment;
import com.foremen.service.payment.dto.ManualPayInput;
import com.foremen.service.payment.dto.ManualTxnInput;
import com.foremen.service.payment.dto.RePlanRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ABAC mapping + startup-annotation verification for the FOR-06-07 payment controllers (task 8.2):
 * {@link PaymentScheduleController}, {@link BankFeedIngestController}, and the intentionally
 * unguarded {@link PaymentWebhookController}.
 *
 * <p>Asserts, at the annotation/resolution level, the ABAC guarantees Requirement 11 and the startup
 * {@code PermissionAnnotationValidator} depend on. It exercises the <b>real</b>
 * {@link PermissionResolver} — the exact code path the request-time {@code PermissionInterceptor}
 * runs and the per-handler check the startup {@code PermissionAnnotationValidator}
 * ({@code SmartInitializingSingleton}) performs — so it asserts the identical logic without booting a
 * Spring context or Testcontainers, mirroring the sibling {@link ProcurementDocumentControllerAbacTest}
 * / {@link EstimateMaterialsControllerAbacTest} / {@link DocumentSigningControllersAbacTest} pattern.
 *
 * <ol>
 *   <li><b>Resource + reachability (R11.1).</b> The two guarded controllers carry class-level
 *       {@code @PermissionResource("PAYMENTS")} — the project-scoped resource seeded by changeset 175
 *       — so {@code PAYMENTS} is reachable only with the right {@code (resource, operation)} pair; the
 *       pair resolves per handler to the standard CRUD operation the design mandates (no new ABAC
 *       operation).</li>
 *   <li><b>Operation mapping (R11.1).</b> {@code create} → {@code PAYMENTS}/{@code CREATE}; every
 *       mutating action (generate / publish / re-plan / add-/edit-/remove-/reorder-tranche /
 *       add-tranche-anytime / mark-paid / record-manual / initiate-online / bank-feed ingest) →
 *       {@code PAYMENTS}/{@code UPDATE}; every read → {@code PAYMENTS}/{@code READ}. The matrix
 *       (changeset 175) grants WORKER nothing (denied), FOREMAN READ only (read-only) — so a
 *       WORKER cannot reach any handler and a FOREMAN reaches only the READ-mapped handlers; the
 *       (own) project-membership scoping is enforced by {@code ProjectScopedService} on top
 *       (R11.2, R11.3).</li>
 *   <li><b>Startup completeness (R11.4, {@code PermissionAnnotationValidator}).</b> Every in-scope
 *       handler of the two guarded controllers classifies
 *       {@link PermissionResolver.Completeness#COMPLETE} — none half-annotated
 *       ({@code RESOURCE_WITHOUT_OPERATION} / {@code OPERATION_WITHOUT_RESOURCE}), none UNGUARDED — so
 *       a loading application context would not fail on these controllers and the app starts clean
 *       with {@code PaymentScheduleController} fully annotated.</li>
 *   <li><b>Public webhook deliberately unguarded.</b> {@link PaymentWebhookController} carries
 *       <b>none</b> of the three annotations (like {@link AuthController} /
 *       {@link SignatureCallbackController} per {@code entity-creation-rules}); its handler resolves
 *       to {@code null} (UNGUARDED) yet still classifies {@code COMPLETE}, so it neither becomes
 *       ABAC-protected nor fails startup — authenticity is enforced by the provider signature
 *       instead (R7.5).</li>
 * </ol>
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3, 11.4, 7.5
 */
@DisplayName("Payments controllers ABAC mapping + startup completeness (PAYMENTS)")
class PaymentsControllersAbacTest {

    private static final String PAYMENTS = "PAYMENTS";
    private static final String CREATE = "CREATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final PermissionResolver resolver = new PermissionResolver();

    // Bare controller instances used solely as the bean for HandlerMethod construction; their null
    // collaborators are never dereferenced because only annotations are inspected.
    private final PaymentScheduleController scheduleController =
            new PaymentScheduleController(null, null, null, null, null, null, null, null);
    private final BankFeedIngestController bankFeedController =
            new BankFeedIngestController(null, null);
    private final PaymentWebhookController webhookController =
            new PaymentWebhookController(null, null);

    // --- class-level guarding: the PAYMENTS resource (R11.1, entity-creation-rules step 3) ---

    @Nested
    @DisplayName("class-level @PermissionResource(\"PAYMENTS\")")
    class ResourceDeclaration {

        @Test
        @DisplayName("PaymentScheduleController carries @PermissionResource(\"PAYMENTS\")")
        void scheduleControllerDeclaresPaymentsResource() {
            assertResource(PaymentScheduleController.class);
        }

        @Test
        @DisplayName("BankFeedIngestController carries @PermissionResource(\"PAYMENTS\")")
        void bankFeedControllerDeclaresPaymentsResource() {
            assertResource(BankFeedIngestController.class);
        }

        private void assertResource(Class<?> controller) {
            PermissionResource annotation = controller.getAnnotation(PermissionResource.class);
            assertThat(annotation)
                    .as("%s must carry class-level @PermissionResource", controller.getSimpleName())
                    .isNotNull();
            assertThat(annotation.value())
                    .as("class-level resource code must be PAYMENTS (changeset 175)")
                    .isEqualTo(PAYMENTS);
        }
    }

    // --- PaymentScheduleController: endpoint -> (resource, operation) mapping (R11.1) ---

    @Nested
    @DisplayName("PaymentScheduleController endpoint -> (PAYMENTS, op) mapping (R11.1)")
    class ScheduleMapping {

        @Test
        @DisplayName("POST /schedules (create) -> (PAYMENTS, CREATE)")
        void createIsPaymentsCreate() {
            assertResolvesTo(scheduleHandler("create", CreateScheduleRequest.class), PAYMENTS, CREATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/generate -> (PAYMENTS, UPDATE)")
        void generateIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("generate", Long.class, GenerateRequest.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/publish -> (PAYMENTS, UPDATE)")
        void publishIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("publish", Long.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/re-plan -> (PAYMENTS, UPDATE)")
        void rePlanIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("rePlan", Long.class, RePlanRequest.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/tranches (addTranche) -> (PAYMENTS, UPDATE)")
        void addTrancheIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("addTranche", Long.class, AddTrancheInput.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/tranches/{tid} (editTranche) -> (PAYMENTS, UPDATE)")
        void editTrancheIsPaymentsUpdate() {
            assertResolvesTo(
                    scheduleHandler("editTranche", Long.class, Long.class, AddTrancheInput.class),
                    PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/tranches/{tid}/remove (removeTranche) -> (PAYMENTS, UPDATE)")
        void removeTrancheIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("removeTranche", Long.class, Long.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/reorder -> (PAYMENTS, UPDATE)")
        void reorderIsPaymentsUpdate() {
            assertResolvesTo(scheduleHandler("reorder", Long.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/add-tranche (addTrancheAnytime) -> (PAYMENTS, UPDATE)")
        void addTrancheAnytimeIsPaymentsUpdate() {
            assertResolvesTo(
                    scheduleHandler("addTrancheAnytime", Long.class, AddTrancheInput.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /tranches/{tid}/mark-paid (markTranchePaid) -> (PAYMENTS, UPDATE)")
        void markTranchePaidIsPaymentsUpdate() {
            assertResolvesTo(
                    scheduleHandler("markTranchePaid", Long.class, ManualPayInput.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /schedules/{id}/transactions (recordManual) -> (PAYMENTS, UPDATE)")
        void recordManualIsPaymentsUpdate() {
            assertResolvesTo(
                    scheduleHandler("recordManual", Long.class, ManualTxnInput.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("POST /online/initiate (initiateOnlinePayment) -> (PAYMENTS, UPDATE)")
        void initiateOnlineIsPaymentsUpdate() {
            assertResolvesTo(
                    scheduleHandler("initiateOnlinePayment", InitiatePayment.class), PAYMENTS, UPDATE);
        }

        @Test
        @DisplayName("GET /schedules/{id}/transactions (listTransactions) -> (PAYMENTS, READ)")
        void listTransactionsIsPaymentsRead() {
            assertResolvesTo(scheduleHandler("listTransactions", Long.class), PAYMENTS, READ);
        }

        @Test
        @DisplayName("GET /schedules/{id} (getSchedule) -> (PAYMENTS, READ)")
        void getScheduleIsPaymentsRead() {
            assertResolvesTo(scheduleHandler("getSchedule", Long.class), PAYMENTS, READ);
        }

        @Test
        @DisplayName("GET /projects/{id}/schedule (getScheduleByProject) -> (PAYMENTS, READ)")
        void getScheduleByProjectIsPaymentsRead() {
            assertResolvesTo(scheduleHandler("getScheduleByProject", Long.class), PAYMENTS, READ);
        }
    }

    // --- BankFeedIngestController mapping: ingest -> (PAYMENTS, UPDATE) (R8.3, R11.4) ---

    @Test
    @DisplayName("BankFeedIngestController POST /ingest -> (PAYMENTS, UPDATE)")
    void bankFeedIngestIsPaymentsUpdate() {
        assertResolvesTo(bankFeedHandler("ingest"), PAYMENTS, UPDATE);
    }

    // --- startup completeness — every guarded handler COMPLETE (PermissionAnnotationValidator, R11.4) ---

    @Nested
    @DisplayName("startup completeness — every guarded handler COMPLETE (R11.4)")
    class StartupCompleteness {

        @Test
        @DisplayName("every PaymentScheduleController handler classifies COMPLETE")
        void everyScheduleHandlerComplete() {
            assertComplete(scheduleHandler("create", CreateScheduleRequest.class));
            assertComplete(scheduleHandler("generate", Long.class, GenerateRequest.class));
            assertComplete(scheduleHandler("publish", Long.class));
            assertComplete(scheduleHandler("rePlan", Long.class, RePlanRequest.class));
            assertComplete(scheduleHandler("addTranche", Long.class, AddTrancheInput.class));
            assertComplete(scheduleHandler("editTranche", Long.class, Long.class, AddTrancheInput.class));
            assertComplete(scheduleHandler("removeTranche", Long.class, Long.class));
            assertComplete(scheduleHandler("reorder", Long.class));
            assertComplete(scheduleHandler("addTrancheAnytime", Long.class, AddTrancheInput.class));
            assertComplete(scheduleHandler("markTranchePaid", Long.class, ManualPayInput.class));
            assertComplete(scheduleHandler("recordManual", Long.class, ManualTxnInput.class));
            assertComplete(scheduleHandler("initiateOnlinePayment", InitiatePayment.class));
            assertComplete(scheduleHandler("listTransactions", Long.class));
            assertComplete(scheduleHandler("getSchedule", Long.class));
            assertComplete(scheduleHandler("getScheduleByProject", Long.class));
        }

        @Test
        @DisplayName("BankFeedIngestController ingest handler classifies COMPLETE")
        void bankFeedIngestComplete() {
            assertComplete(bankFeedHandler("ingest"));
        }
    }

    // --- public webhook deliberately unguarded (R7.5; AuthController-style) ---

    @Nested
    @DisplayName("PaymentWebhookController deliberately unguarded (public przelewy24 webhook)")
    class PublicWebhook {

        @Test
        @DisplayName("controller carries NONE of the three permission annotations")
        void webhookControllerCarriesNoPermissionAnnotations() {
            assertThat(PaymentWebhookController.class.getAnnotation(PermissionResource.class))
                    .as("the public webhook must NOT carry @PermissionResource (like AuthController)")
                    .isNull();
        }

        @Test
        @DisplayName("online webhook handler carries neither @PermissionOperation nor @RequiresPermission")
        void webhookHandlerCarriesNoMethodPermissionAnnotations() throws NoSuchMethodException {
            Method online = PaymentWebhookController.class.getMethod("online", HttpServletRequest.class);
            assertThat(online.getAnnotation(PermissionOperation.class))
                    .as("the webhook handler must not carry @PermissionOperation").isNull();
            assertThat(online.getAnnotation(RequiresPermission.class))
                    .as("the webhook handler must not carry @RequiresPermission").isNull();
        }

        @Test
        @DisplayName("online webhook handler resolves to null (UNGUARDED) yet classifies COMPLETE")
        void webhookHandlerUnguardedButComplete() {
            HandlerMethod handler = webhookHandler("online", HttpServletRequest.class);
            assertThat(resolver.resolve(handler))
                    .as("a deliberately unguarded public webhook resolves to no (resource, operation) pair")
                    .isNull();
            assertThat(resolver.classifyCompleteness(handler))
                    .as("a controller carrying none of the three annotations is deliberately unguarded, "
                            + "classified COMPLETE so PermissionAnnotationValidator does not fail startup")
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
        }
    }

    // --- helpers ---

    private HandlerMethod scheduleHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(scheduleController, PaymentScheduleController.class, methodName, paramTypes);
    }

    private HandlerMethod bankFeedHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(bankFeedController, BankFeedIngestController.class, methodName, paramTypes);
    }

    private HandlerMethod webhookHandler(String methodName, Class<?>... paramTypes) {
        return handlerFor(webhookController, PaymentWebhookController.class, methodName, paramTypes);
    }

    private HandlerMethod handlerFor(Object bean, Class<?> controllerType, String methodName,
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
}
