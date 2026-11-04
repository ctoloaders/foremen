package com.foremen.controller;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionInterceptor;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.ProcurementPaidInCashRequest;
import com.foremen.controller.model.mapper.ProcurementDocumentControllerMapperImpl;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.ProcurementPaymentChoice;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProcurementDocumentService;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.procurement.ProcurementLedgerEntryDto;

/**
 * ABAC + startup-annotation verification for {@link ProcurementDocumentController} (FOR-06-03 task
 * 6.1), extended by FOR-07-04 task 7.6 (upload payment choice + {@code POST /{id}/paid-in-cash}).
 *
 * <p>Asserts, at the annotation/resolution level, the two guarantees the runtime ABAC stack and the
 * startup {@code PermissionAnnotationValidator} depend on (design §5.5, entity-creation-rules step
 * 3):
 * <ol>
 *   <li><b>Endpoint → (resource, operation) mapping (R6.1, R7.3, R8.1).</b> Each handler resolves
 *       through the real {@link PermissionResolver#resolve(HandlerMethod)} (the exact code path the
 *       request-time {@code PermissionInterceptor} runs) to the pair the design mandates: upload →
 *       {@code PURCHASES}/{@code CREATE}, list / read-one / images → {@code PURCHASES}/{@code READ},
 *       re-process / paid-in-cash → {@code PURCHASES}/{@code UPDATE}, delete →
 *       {@code PURCHASES}/{@code DELETE}.</li>
 *   <li><b>Startup completeness (R7.3, {@code PermissionAnnotationValidator}).</b> Every in-scope
 *       handler classifies {@link PermissionResolver.Completeness#COMPLETE}.</li>
 * </ol>
 *
 * <p>FOR-07-04 additions: the real {@link PermissionInterceptor} (real resolver, stubbed evaluator)
 * allows / denies {@code paid-in-cash} by the {@code PURCHASES}/{@code UPDATE} grant, and a standalone
 * MockMvc (mocked service, real MapStruct mapper) proves the upload binds the optional payment fields
 * and that {@code paymentStatus} / {@code ledgerEntries} reach the JSON.
 *
 * <p>Validates: Requirements 6.1, 7.3, 8.1 (FOR-06-03); 18.3, 28.1, 28.4 (FOR-07-04)
 */
@DisplayName("ProcurementDocumentController ABAC mapping + startup completeness")
class ProcurementDocumentControllerAbacTest {

    private static final String PURCHASES = "PURCHASES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private static final Class<?>[] UPLOAD_PARAMS = {Long.class, MultipartFile.class, Long.class,
            ProcurementPaymentChoice.class, Long.class, BigDecimal.class, LocalDate.class};
    private static final Class<?>[] UPLOAD_COMPANY_PARAMS = {Long.class, MultipartFile.class,
            ProcurementPaymentChoice.class, Long.class, BigDecimal.class, LocalDate.class};
    private static final Class<?>[] PAID_IN_CASH_PARAMS = {Long.class, ProcurementPaidInCashRequest.class};

    private final PermissionResolver resolver = new PermissionResolver();

    /**
     * A bare controller instance used solely as the bean for {@link HandlerMethod} construction; its
     * {@code null} collaborators are never dereferenced because only annotations are inspected.
     */
    private final ProcurementDocumentController controller =
            new ProcurementDocumentController(null, null);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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
        assertResolvesTo(handlerFor("upload", UPLOAD_PARAMS), PURCHASES, CREATE);
    }

    @Test
    @DisplayName("POST /company (ledger upload, optional project) → (PURCHASES, CREATE), COMPLETE")
    void uploadCompanyIsPurchasesCreate() {
        HandlerMethod handler = handlerFor("uploadCompany", UPLOAD_COMPANY_PARAMS);
        assertResolvesTo(handler, PURCHASES, CREATE);
        assertComplete(handler);
    }

    @Test
    @DisplayName("POST /company without projectId accepts LINKED_FROM_LEDGER and passes null project")
    void uploadCompanyWithoutProject() throws Exception {
        ProcurementDocumentService service = mock(ProcurementDocumentService.class);
        when(service.upload(isNull(), any(MultipartFile.class),
                any(ProcurementDocumentService.UploadPaymentRequest.class))).thenReturn(paidModel());
        MockMvc mvc = mockMvc(service);

        mvc.perform(multipart("/api/procurement-documents/company")
                        .file(new MockMultipartFile("file", "r.jpg", "image/jpeg", new byte[] {1}))
                        .param("paymentChoice", "LINKED_FROM_LEDGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11));

        verify(service).upload(isNull(), any(MultipartFile.class),
                eq(new ProcurementDocumentService.UploadPaymentRequest(
                        ProcurementPaymentChoice.LINKED_FROM_LEDGER, null, null, null)));
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
    @DisplayName("POST /{id}/paid-in-cash → (PURCHASES, UPDATE)")
    void paidInCashIsPurchasesUpdate() {
        assertResolvesTo(handlerFor("paidInCash", PAID_IN_CASH_PARAMS), PURCHASES, UPDATE);
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
        assertComplete(handlerFor("upload", UPLOAD_PARAMS));
        assertComplete(handlerFor("list", Long.class, Pageable.class));
        assertComplete(handlerFor("findById", Long.class));
        assertComplete(handlerFor("reprocess", Long.class));
        assertComplete(handlerFor("paidInCash", PAID_IN_CASH_PARAMS));
        assertComplete(handlerFor("delete", Long.class));
        assertComplete(handlerFor("images", Long.class));
    }

    // --- FOR-07-04: paid-in-cash allowed / denied by the PURCHASES UPDATE grant (R28.4) ---

    @Test
    @DisplayName("paid-in-cash: a role with PURCHASES UPDATE is allowed through the interceptor")
    void paidInCashAllowedWithPurchasesUpdate() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        when(evaluator.isAllowed("MANAGER", PURCHASES, UPDATE)).thenReturn(true);
        authenticateAs("MANAGER");

        boolean proceed = new PermissionInterceptor(evaluator, resolver).preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                handlerFor("paidInCash", PAID_IN_CASH_PARAMS));

        assertThat(proceed).isTrue();
        verify(evaluator).isAllowed("MANAGER", PURCHASES, UPDATE);
    }

    @Test
    @DisplayName("paid-in-cash: a role without PURCHASES UPDATE is denied with 403 error.access.denied")
    void paidInCashDeniedWithoutPurchasesUpdate() {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        when(evaluator.isAllowed("WORKER", PURCHASES, UPDATE)).thenReturn(false);
        authenticateAs("WORKER");
        PermissionInterceptor interceptor = new PermissionInterceptor(evaluator, resolver);
        HandlerMethod handler = handlerFor("paidInCash", PAID_IN_CASH_PARAMS);

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
                });
        verify(evaluator).isAllowed("WORKER", PURCHASES, UPDATE);
    }

    // --- FOR-07-04: request binding + JSON shape (R18.3, R28.1, R28.4) ---

    @Test
    @DisplayName("upload binds paymentChoice + cash fields and echoes paymentStatus / ledgerEntries")
    void uploadBindsPaymentChoice() throws Exception {
        ProcurementDocumentService service = mock(ProcurementDocumentService.class);
        when(service.upload(eq(7L), any(MultipartFile.class), isNull(), any(ProcurementDocumentService.UploadPaymentRequest.class)))
                .thenReturn(paidModel());
        MockMvc mvc = mockMvc(service);

        mvc.perform(multipart("/api/procurement-documents")
                        .file(new MockMultipartFile("file", "r.jpg", "image/jpeg", new byte[] {1, 2}))
                        .param("projectId", "7")
                        .param("paymentChoice", "PAID_CASH")
                        .param("cashAccountId", "3")
                        .param("cashAmount", "123.45")
                        .param("cashDate", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.ledgerEntries[0].transactionId").value(501))
                .andExpect(jsonPath("$.ledgerEntries[0].channel").value("CASH"));

        verify(service).upload(eq(7L), any(MultipartFile.class), isNull(), eq(new ProcurementDocumentService.UploadPaymentRequest(
                ProcurementPaymentChoice.PAID_CASH, 3L, new BigDecimal("123.45"), LocalDate.of(2026, 7, 15))));
    }

    @Test
    @DisplayName("upload without payment fields stays backward compatible (null choice ⇒ NOT_LINKED)")
    void uploadWithoutPaymentFields() throws Exception {
        ProcurementDocumentService service = mock(ProcurementDocumentService.class);
        when(service.upload(eq(7L), any(MultipartFile.class), isNull(), any(ProcurementDocumentService.UploadPaymentRequest.class)))
                .thenReturn(paidModel());
        MockMvc mvc = mockMvc(service);

        mvc.perform(multipart("/api/procurement-documents")
                        .file(new MockMultipartFile("file", "r.jpg", "image/jpeg", new byte[] {1}))
                        .param("projectId", "7"))
                .andExpect(status().isOk());

        verify(service).upload(eq(7L), any(MultipartFile.class), isNull(),
                eq(new ProcurementDocumentService.UploadPaymentRequest(null, null, null, null)));
        assertThat(new ProcurementDocumentService.UploadPaymentRequest(null, null, null, null).effectiveChoice())
                .isEqualTo(ProcurementPaymentChoice.NOT_LINKED);
    }

    @Test
    @DisplayName("POST /{id}/paid-in-cash passes the body to markPaidInCash and returns the detail DTO")
    void paidInCashDelegates() throws Exception {
        ProcurementDocumentService service = mock(ProcurementDocumentService.class);
        when(service.markPaidInCash(11L, 3L, new BigDecimal("50.00"), LocalDate.of(2026, 7, 20)))
                .thenReturn(paidModel());
        MockMvc mvc = mockMvc(service);

        mvc.perform(post("/api/procurement-documents/11/paid-in-cash")
                        .contentType("application/json")
                        .content("{\"cashAccountId\":3,\"amount\":50.00,\"date\":\"2026-07-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.ledgerEntries[0].transactionId").value(501));
    }

    @Test
    @DisplayName("POST /{id}/paid-in-cash without a body uses all defaults (nulls)")
    void paidInCashWithoutBody() throws Exception {
        ProcurementDocumentService service = mock(ProcurementDocumentService.class);
        when(service.markPaidInCash(eq(11L), isNull(), isNull(), isNull())).thenReturn(paidModel());
        MockMvc mvc = mockMvc(service);

        mvc.perform(post("/api/procurement-documents/11/paid-in-cash"))
                .andExpect(status().isOk());

        verify(service).markPaidInCash(11L, null, null, null);
    }

    // --- helpers ---

    private static MockMvc mockMvc(ProcurementDocumentService service) {
        return MockMvcBuilders.standaloneSetup(
                new ProcurementDocumentController(service, new ProcurementDocumentControllerMapperImpl()))
                .build();
    }

    private static ProcurementDocumentServiceExtendedModel paidModel() {
        ProcurementDocumentServiceExtendedModel model = new ProcurementDocumentServiceExtendedModel();
        model.setId(11L);
        model.setProjectId(7L);
        model.setPaymentStatus(DocumentPaymentStatus.PAID);
        model.setLedgerEntries(List.of(
                new ProcurementLedgerEntryDto(501L, "CASH", null, "Cash PLN", new BigDecimal("123.45"))));
        return model;
    }

    private static void authenticateAs(String roleCode) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "42", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

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
