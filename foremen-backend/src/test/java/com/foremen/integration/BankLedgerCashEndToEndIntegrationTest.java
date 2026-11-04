package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SystemSource;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.procurement.ProcurementDocumentParsedEvent;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-07-04 task 8.1 — the cash side of the company money ledger end to end over HTTP, real beans and
 * the real Liquibase schema (Testcontainers PostgreSQL):
 * <ul>
 *   <li>a manual cash entry with a link in the same request (R27.3) — the linked payout is PAID from the
 *       ledger, so no SYSTEM entry is created for it (R27.4 / R27.5);</li>
 *   <li>a CASH payout marked paid in payroll creates exactly one SYSTEM cash entry, linked, and the payout
 *       shows it in {@code ledgerEntries} (R27.4);</li>
 *   <li>procurement {@code PAID_CASH} on upload and "mark paid in cash" (R28.1);</li>
 *   <li>a {@code NOT_LINKED} upload, parsed later, is reverse-matched to the existing cash line by the
 *       {@code ProcurementDocumentParsedEvent} listener (R28.2).</li>
 * </ul>
 * Only the external object store is replaced ({@link InMemoryImageStorageConfig}); the OCR provider is
 * disabled, so an upload rests {@code UPLOADED} and the parse outcome is written the way the OCR worker
 * writes it before it publishes the parsed event.
 *
 * <p>Repeatability: a fresh container per run; users, projects, invoice numbers and amounts carry a
 * per-run id / random value.
 *
 * <p>Validates: Requirements 27.4, 28.2 (R27.3, R27.5, R28.1, R28.4, R16.3)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@Import(InMemoryImageStorageConfig.class)
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 8.1: cash end to end")
class BankLedgerCashEndToEndIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (BankLedgerCashEndToEndIntegrationTest.class) {
            if (!migrated) {
                BankLedgerItSupport.migrate(POSTGRES);
                migrated = true;
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private WorkerPayoutDao payoutDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final Random random = new Random();
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int seq;
    private String auth;

    @BeforeEach
    void setUp() {
        UserEntity admin = tx().execute(s -> newUser("Cash E2E admin", "ADMIN"));
        auth = "Bearer " + jwt.generateAccessToken(admin.getId(), "ADMIN", admin.getEmail());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // =============================================================================================
    // Manual cash entry with a link (R27.3) — ledger-originated payout: no SYSTEM entry (R27.5)
    // =============================================================================================

    @Test
    @DisplayName("R27.3 / R27.5: a manual cash entry linked to a payout pays it in CASH; no SYSTEM entry is created for it")
    void manualEntryWithLink() throws Exception {
        BigDecimal amount = uniqueAmount();
        Long payoutId = tx().execute(s -> newPayout("Gotowka Pracownik " + runId, amount, PaymentChannel.BANK_TRANSFER).getId());

        long cashAccountId = cashAccount();
        String created = body(perform(post("/api/bank/transactions/cash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + cashAccountId + ",\"direction\":\"EXPENSE\",\"amount\":" + amount
                        + ",\"date\":\"" + today
                        + "\",\"counterpartyName\":\"Pracownik " + runId + "\",\"title\":\"Wypłata gotówką " + runId
                        + "\",\"link\":{\"kind\":\"WORKER_PAYOUT\",\"workerPayoutId\":" + payoutId + "}}")), 201);
        long txnId = JsonPath.<Number>read(created, "$.transactionId").longValue();
        assertThat(JsonPath.<String>read(created, "$.linkStatus")).isEqualTo("LINKED");
        assertThat(new BigDecimal(JsonPath.read(created, "$.amount").toString())).isEqualByComparingTo(amount.negate());

        WorkerPayoutEntity payout = payoutDao.findById(payoutId).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payout.getPaymentChannel()).isEqualTo(PaymentChannel.CASH);
        assertThat(transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, payoutId))
                .as("ledger-originated payment → no SYSTEM entry").isEmpty();
        assertThat(linkDao.findByWorkerPayoutId(payoutId)).hasSize(1);

        String detail = body(perform(get("/api/bank/transactions/" + txnId)), 200);
        assertThat(JsonPath.<String>read(detail, "$.line.channel")).isEqualTo("CASH");
        assertThat(JsonPath.<String>read(detail, "$.line.origin")).isEqualTo("MANUAL");
        assertThat(JsonPath.<Boolean>read(detail, "$.line.editable")).isTrue();
        assertThat(JsonPath.<Number>read(detail, "$.line.links[0].workerPayoutId").longValue()).isEqualTo(payoutId);

        String page = body(perform(get("/api/bank/transactions").param("channel", "CASH")
                .param("transactionId", String.valueOf(txnId))), 200);
        assertThat(JsonPath.<List<Number>>read(page, "$.content[*].id")).extracting(Number::longValue).containsExactly(txnId);
    }

    // =============================================================================================
    // CASH payout paid in payroll → exactly one SYSTEM entry (R27.4)
    // =============================================================================================

    @Test
    @DisplayName("R27.4: a CASH payout marked paid in payroll creates exactly one linked SYSTEM cash entry, shown in ledgerEntries")
    void cashPayoutInPayrollCreatesOneSystemEntry() throws Exception {
        BigDecimal amount = uniqueAmount();
        Long payoutId = tx().execute(s -> newPayout("Kasa Pracownik " + runId, amount, PaymentChannel.CASH).getId());
        Long workerId = tx().execute(s -> payoutDao.findById(payoutId).orElseThrow().getWorker().getId());

        String paid = body(perform(post("/api/worker-payouts/" + payoutId + "/pay")), 200);
        assertThat(JsonPath.<String>read(paid, "$.status")).isEqualTo("PAID");
        assertThat(perform(post("/api/worker-payouts/" + payoutId + "/pay")).getResponse().getStatus())
                .as("a second pay is refused").isEqualTo(409);

        Optional<BankTransactionEntity> entry = transactionDao
                .findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, payoutId);
        assertThat(entry).isPresent();
        BankTransactionEntity t = entry.get();
        assertThat(t.getChannel()).isEqualTo(LedgerChannel.CASH);
        assertThat(t.getOrigin()).isEqualTo(LedgerOrigin.SYSTEM);
        assertThat(t.getAmount()).isEqualByComparingTo(amount.negate());
        assertThat(t.getLinkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(linkDao.findByWorkerPayoutId(payoutId)).singleElement()
                .satisfies(l -> assertThat(l.getTransaction().getId()).isEqualTo(t.getId()));
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES,
                "SELECT COUNT(*) FROM bank_transactions WHERE system_source = 'PAYROLL_PAYOUT' AND system_source_id = ?",
                payoutId)).as("exactly one SYSTEM entry").isEqualTo(1);

        String payouts = body(perform(get("/api/worker-payouts").param("workerId", String.valueOf(workerId))), 200);
        assertThat(JsonPath.<List<Number>>read(payouts, "$[0].ledgerEntries[*].transactionId"))
                .extracting(Number::longValue).containsExactly(t.getId());
        assertThat(JsonPath.<String>read(payouts, "$[0].ledgerEntries[0].channel")).isEqualTo("CASH");

        String detail = body(perform(get("/api/bank/transactions/" + t.getId())), 200);
        assertThat(JsonPath.<String>read(detail, "$.systemSource")).isEqualTo("PAYROLL_PAYOUT");
        assertThat(JsonPath.<Boolean>read(detail, "$.line.editable")).as("SYSTEM entries are read-only").isFalse();
        assertThat(perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/bank/transactions/" + t.getId())).getResponse().getStatus()).isEqualTo(409);
    }

    // =============================================================================================
    // Procurement PAID_CASH (R28.1, R28.4)
    // =============================================================================================

    @Test
    @DisplayName("R28.1: upload with PAID_CASH creates a linked SYSTEM cash entry; mark paid in cash on a document with a total → PAID")
    void procurementPaidCash() throws Exception {
        Long projectId = tx().execute(s -> newProject().getId());
        BigDecimal cashAmount = uniqueAmount();

        String uploaded = body(perform(multipart("/api/procurement-documents")
                .file(pdf())
                .param("projectId", String.valueOf(projectId))
                .param("paymentChoice", "PAID_CASH")
                .param("cashAmount", cashAmount.toPlainString())
                .param("cashDate", today.minusDays(1).toString())), 200);
        long docId = JsonPath.<Number>read(uploaded, "$.id").longValue();
        assertThat(JsonPath.<String>read(uploaded, "$.paymentStatus"))
                .as("total unknown until OCR, Σ > 0").isEqualTo("PARTIALLY_PAID");
        assertThat(JsonPath.<List<Object>>read(uploaded, "$.ledgerEntries")).hasSize(1);
        BankTransactionEntity entry = transactionDao
                .findBySystemSourceAndSystemSourceId(SystemSource.PROCUREMENT_CASH, docId).orElseThrow();
        assertThat(entry.getChannel()).isEqualTo(LedgerChannel.CASH);
        assertThat(entry.getAmount()).isEqualByComparingTo(cashAmount.negate());
        assertThat(entry.getBookingDate()).isEqualTo(today.minusDays(1));
        assertThat(linkDao.findByProcurementDocumentId(docId)).singleElement()
                .satisfies(l -> assertThat(l.getAmount()).isEqualByComparingTo(cashAmount));

        // Mark paid in cash on an UNMATCHED document with a known total (amount defaults to the total).
        BigDecimal total = uniqueAmount();
        Long doc2 = tx().execute(s -> newParsedDocument(projectId, total, "FV/" + runId + "/CASH").getId());
        String paid = body(perform(post("/api/procurement-documents/" + doc2 + "/paid-in-cash")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 200);
        assertThat(JsonPath.<String>read(paid, "$.paymentStatus")).isEqualTo("PAID");
        assertThat(JsonPath.<List<Object>>read(paid, "$.ledgerEntries")).hasSize(1);
        assertThat(transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PROCUREMENT_CASH, doc2).orElseThrow()
                .getAmount()).isEqualByComparingTo(total.negate());
        assertThat(perform(post("/api/procurement-documents/" + doc2 + "/paid-in-cash")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).getResponse().getStatus())
                .as("a paid document is no longer UNMATCHED").isEqualTo(409);
    }

    // =============================================================================================
    // NOT_LINKED document reverse-matched after the parsed event (R28.2)
    // =============================================================================================

    @Test
    @DisplayName("R28.2: a NOT_LINKED upload, once parsed, is reverse-matched to the existing cash line and becomes PAID")
    void notLinkedDocumentReverseMatchedAfterParse() throws Exception {
        Long projectId = tx().execute(s -> newProject().getId());
        BigDecimal total = uniqueAmount();
        String invoice = "FV/" + runId + "/REV";
        String supplier = "Hurtownia Rewers " + runId;
        long cashAccountId = cashAccount();
        String line = body(perform(post("/api/bank/transactions/cash").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + cashAccountId + ",\"direction\":\"EXPENSE\",\"amount\":" + total
                        + ",\"date\":\"" + today
                        + "\",\"counterpartyName\":\"" + supplier + "\",\"title\":\"Faktura " + invoice + "\"}")), 201);
        long txnId = JsonPath.<Number>read(line, "$.transactionId").longValue();
        assertThat(JsonPath.<String>read(line, "$.linkStatus")).isEqualTo("UNLINKED");

        String uploaded = body(perform(multipart("/api/procurement-documents")
                .file(pdf())
                .param("projectId", String.valueOf(projectId))
                .param("paymentChoice", "NOT_LINKED")), 200);
        long docId = JsonPath.<Number>read(uploaded, "$.id").longValue();
        assertThat(JsonPath.<String>read(uploaded, "$.paymentStatus")).isEqualTo("UNMATCHED");
        assertThat(linkDao.findByProcurementDocumentId(docId)).isEmpty();

        // What the OCR worker persists on the PARSED transition, then the event it publishes (R18.1, R18.2).
        tx().executeWithoutResult(s -> {
            ProcurementDocumentEntity d = documentDao.findById(docId).orElseThrow();
            d.setStatus(ProcessingStatus.PARSED);
            d.setFailureReason(null);
            d.setDocumentTotal(total);
            d.setInvoiceNumber(invoice);
            d.setInvoiceDate(today);
            d.setSupplierName(supplier);
            documentDao.save(d);
            events.publishEvent(new ProcurementDocumentParsedEvent(docId));
        });

        long deadline = System.currentTimeMillis() + 15_000;
        LinkStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            status = transactionDao.findById(txnId).orElseThrow().getLinkStatus();
            if (status != LinkStatus.UNLINKED) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("reverse match auto-links the cash line").isEqualTo(LinkStatus.AUTO_LINKED);
        String doc = body(perform(get("/api/procurement-documents/" + docId)), 200);
        assertThat(JsonPath.<String>read(doc, "$.paymentStatus")).isEqualTo("PAID");
        assertThat(JsonPath.<List<Number>>read(doc, "$.ledgerEntries[*].transactionId"))
                .extracting(Number::longValue).containsExactly(txnId);
        String detail = body(perform(get("/api/bank/transactions/" + txnId)), 200);
        assertThat(JsonPath.<String>read(detail, "$.line.links[0].kind")).isEqualTo("PROCUREMENT");
        assertThat(JsonPath.<String>read(detail, "$.line.links[0].source")).isEqualTo("RULE");
    }

    // =============================================================================================
    // helpers
    // =============================================================================================

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth)).andReturn();
    }

    private static String body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("%s %s → %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), content)
                .isEqualTo(expectedStatus);
        return content;
    }

    /** A fresh (non-default) PLN cash register created over {@code POST /api/bank/cash-accounts}. */
    private long cashAccount() throws Exception {
        String created = body(perform(post("/api/bank/cash-accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Kasa " + runId + "-" + (++seq) + "\",\"currency\":\"PLN\",\"defaultForCash\":false}")), 201);
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "faktura-" + runId + ".pdf", "application/pdf",
                ("%PDF-1.4\n% test " + runId + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
    }

    /** A random amount in 1000.00..9999.99, practically unique within a run. */
    private BigDecimal uniqueAmount() {
        return BigDecimal.valueOf(100_000 + random.nextInt(899_999), 2);
    }

    private UserEntity newUser(String name, String roleCode) {
        int n = ++seq;
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail("cash-e2e+" + runId.toLowerCase(Locale.ROOT) + "-" + n + "@example.com");
        user.setRole(roleDao.findByCode(roleCode).orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    private ProjectEntity newProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("Cash E2E project " + runId + "-" + (++seq));
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private WorkerPayoutEntity newPayout(String workerName, BigDecimal amount, PaymentChannel channel) {
        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setWorker(newUser(workerName, "WORKER"));
        payout.setPayoutDate(today);
        payout.setPeriodFrom(today.minusDays(7));
        payout.setPeriodTo(today.minusDays(1));
        payout.setAmount(amount);
        payout.setSuggestedAmount(amount);
        payout.setPaymentChannel(channel);
        payout.setStatus(PayoutStatus.PLANNED);
        return payoutDao.save(payout);
    }

    private ProcurementDocumentEntity newParsedDocument(Long projectId, BigDecimal total, String invoiceNumber) {
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(projectDao.findById(projectId).orElseThrow());
        document.setUploadedBy(newUser("Uploader " + runId, "ADMIN"));
        document.setRawObjectKey("raw/" + runId + "/" + (++seq));
        document.setSourceMimeType("application/pdf");
        document.setStatus(ProcessingStatus.PARSED);
        document.setUploadedAt(java.time.OffsetDateTime.now(ZoneOffset.UTC));
        document.setDocumentTotal(total);
        document.setInvoiceNumber(invoiceNumber);
        document.setInvoiceDate(today);
        document.setSupplierName("Castorama " + runId);
        return documentDao.save(document);
    }
}
