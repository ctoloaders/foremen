package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResolver.Completeness;
import com.foremen.config.security.PermissionResolver.ResolvedPair;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;

import jakarta.servlet.http.HttpServletRequest;

/**
 * FOR-11-06 task 2.8 (R1.3, R1.4, R1.7, R3.4): ABAC mapping and startup completeness of the unified payment API.
 * Replaces {@code PaymentsControllersAbacTest} (schedule handlers) and {@code ClientProformaControllersAbacTest}
 * for the new surface: every handler of {@link ClientPaymentController} resolves to
 * {@code (CLIENT_PAYMENTS, <standard CRUD operation>)} through the real {@link PermissionResolver}, classifies
 * COMPLETE (so {@code PermissionAnnotationValidator} does not fail startup), and the re-annotated bank-feed
 * ingest and the public webhook keep their guarding. The per-kind rules of the service are covered by
 * {@code ClientPaymentServiceTest}; the end-to-end role matrix by {@code ClientPaymentListIntegrationTest}.
 *
 * <p>Validates: Requirements 1.3, 1.4, 1.7, 3.4
 */
@DisplayName("ClientPaymentController ABAC mapping + startup completeness (CLIENT_PAYMENTS)")
class ClientPaymentControllerAbacTest {

    private static final String RESOURCE = "CLIENT_PAYMENTS";
    private static final String CREATE = "CREATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    /** Handler method name to the operation the design (section 3) assigns it. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("find", READ);
        EXPECTED.put("summary", READ);
        EXPECTED.put("get", READ);
        EXPECTED.put("pdf", READ);
        EXPECTED.put("createMaterials", CREATE);
        EXPECTED.put("update", UPDATE);
        EXPECTED.put("delete", DELETE);
        EXPECTED.put("issue", UPDATE);
        EXPECTED.put("cancel", UPDATE);
        EXPECTED.put("createSchedule", CREATE);
        EXPECTED.put("getScheduleByProject", READ);
        EXPECTED.put("getSchedule", READ);
        EXPECTED.put("generate", UPDATE);
        EXPECTED.put("publish", UPDATE);
        EXPECTED.put("rePlan", UPDATE);
        EXPECTED.put("addTranche", UPDATE);
        EXPECTED.put("editTranche", UPDATE);
        EXPECTED.put("removeTranche", UPDATE);
        EXPECTED.put("reorder", UPDATE);
        EXPECTED.put("addTrancheAnytime", UPDATE);
        EXPECTED.put("markPaid", UPDATE);
        EXPECTED.put("recordManual", UPDATE);
        EXPECTED.put("listTransactions", READ);
        EXPECTED.put("initiateOnlinePayment", UPDATE);
    }

    private final PermissionResolver resolver = new PermissionResolver();

    // Bare instances used solely as the bean of a HandlerMethod; the null collaborators are never touched.
    private final ClientPaymentController controller = new ClientPaymentController(null, null, null, null, null,
            null, null, null, null, null, null, null);
    private final BankFeedIngestController bankFeed = new BankFeedIngestController(null, null);
    private final PaymentWebhookController webhook = new PaymentWebhookController(null, null);

    private static boolean isHandler(Method m) {
        return Arrays.stream(m.getAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
    }

    @Test
    @DisplayName("class-level @PermissionResource(\"CLIENT_PAYMENTS\") on the controller and the bank-feed ingest")
    void classesDeclareClientPaymentsResource() {
        for (Class<?> c : new Class<?>[] {ClientPaymentController.class, BankFeedIngestController.class}) {
            PermissionResource r = c.getAnnotation(PermissionResource.class);
            assertThat(r).as(c.getSimpleName()).isNotNull();
            assertThat(r.value()).as(c.getSimpleName()).isEqualTo(RESOURCE);
        }
        assertThat(ClientPaymentController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/client-payments");
    }

    @Test
    @DisplayName("every handler resolves to (CLIENT_PAYMENTS, expected operation) and none is missing")
    void everyHandlerMapsToTheDesignedOperation() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Method m : ClientPaymentController.class.getDeclaredMethods()) {
            if (!isHandler(m)) {
                continue;
            }
            ResolvedPair pair = resolver.resolve(new HandlerMethod(controller, m));
            assertThat(pair).as("%s must resolve to a pair (UNGUARDED otherwise)", m.getName()).isNotNull();
            assertThat(pair.resource()).as(m.getName()).isEqualTo(RESOURCE);
            actual.put(m.getName(), pair.operation());
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    @Test
    @DisplayName("every handler carries @PermissionOperation, none uses @RequiresPermission, all COMPLETE")
    void everyHandlerIsAnnotatedAndComplete() {
        int handlers = 0;
        for (Method m : ClientPaymentController.class.getDeclaredMethods()) {
            if (!isHandler(m)) {
                continue;
            }
            handlers++;
            assertThat(m.getAnnotation(PermissionOperation.class)).as(m.getName()).isNotNull();
            assertThat(m.getAnnotation(RequiresPermission.class)).as(m.getName()).isNull();
            assertThat(resolver.classifyCompleteness(new HandlerMethod(controller, m))).as(m.getName())
                    .isEqualTo(Completeness.COMPLETE);
        }
        assertThat(handlers).isEqualTo(EXPECTED.size());
    }

    @Test
    @DisplayName("reads are READ only, create endpoints CREATE only, delete is the only DELETE")
    void operationPartition() {
        assertThat(EXPECTED.entrySet().stream().filter(e -> e.getValue().equals(CREATE)).map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("createMaterials", "createSchedule");
        assertThat(EXPECTED.entrySet().stream().filter(e -> e.getValue().equals(DELETE)).map(Map.Entry::getKey))
                .containsExactly("delete");
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            boolean readsOnly = e.getKey().equals("find") || e.getKey().startsWith("get")
                    || e.getKey().equals("summary") || e.getKey().equals("pdf")
                    || e.getKey().equals("listTransactions");
            assertThat(e.getValue().equals(READ)).as(e.getKey()).isEqualTo(readsOnly);
        }
    }

    @Test
    @DisplayName("BankFeedIngestController POST /ingest -> (CLIENT_PAYMENTS, UPDATE), COMPLETE")
    void bankFeedIngestIsClientPaymentsUpdate() throws NoSuchMethodException {
        HandlerMethod ingest = new HandlerMethod(bankFeed, method(BankFeedIngestController.class, "ingest"));
        assertThat(resolver.resolve(ingest)).isEqualTo(new ResolvedPair(RESOURCE, UPDATE));
        assertThat(resolver.classifyCompleteness(ingest)).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    @DisplayName("the public przelewy24 webhook stays deliberately unguarded yet COMPLETE")
    void webhookStaysUnguarded() throws NoSuchMethodException {
        assertThat(PaymentWebhookController.class.getAnnotation(PermissionResource.class)).isNull();
        Method online = PaymentWebhookController.class.getMethod("online", HttpServletRequest.class);
        HandlerMethod handler = new HandlerMethod(webhook, online);
        assertThat(resolver.resolve(handler)).isNull();
        assertThat(resolver.classifyCompleteness(handler)).isEqualTo(Completeness.COMPLETE);
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        return Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new NoSuchMethodException(type.getSimpleName() + "." + name));
    }
}
