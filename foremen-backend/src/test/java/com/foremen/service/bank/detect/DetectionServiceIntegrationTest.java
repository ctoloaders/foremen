package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.bank.cash.CashDirection;
import com.foremen.service.bank.cash.CashEntryRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.procurement.ProcurementDocumentParsedEvent;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 6.8 — the detection pipeline end to end against the real schema (Liquibase changelog on
 * Testcontainers PostgreSQL): candidate providers → {@link RuleMatcher} → {@code BankLinkService}
 * (auto-link with effects / suggestions), reverse document matching (direct call and through the
 * {@link ProcurementDocumentParsedEvent} listener) and re-run.
 *
 * <p>Repeatability: a fresh container per run; every line, worker, document and amount carries a per-run
 * id / random amount, so tests in the class never match each other's data.
 *
 * <p>Validates: Requirements 14.1, 14.5, 14.6, 14.7, 17.6, 27.7, 28.2, 28.3
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 6.8: detection")
class DetectionServiceIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyMigrationsOnce();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static synchronized void applyMigrationsOnce() throws Exception {
        if (migrated) {
            return;
        }
        try {
            runLiquibase();
        } catch (Exception first) {
            seedAdminUser();
            runLiquibase();
        }
        migrated = true;
    }

    private static Connection newJdbcConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void runLiquibase() throws Exception {
        try (Connection connection = newJdbcConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUser() throws Exception {
        try (Connection c = newJdbcConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1")) {
                if (!rs.next()) {
                    return;
                }
                long roleId = rs.getLong(1);
                st.execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                        + "VALUES ('mig-admin', 'mig-admin@example.com', " + roleId + ", true, 'ru', NOW(), 'test')");
            }
        }
    }

    @Autowired private DetectionService detectionService;
    @Autowired private ReverseDocumentMatcher reverseMatcher;
    @Autowired private CashLedgerService cashService;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private BankTransactionSuggestionDao suggestionDao;
    @Autowired private WorkerPayoutDao payoutDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final Random random = new Random();
    private final LocalDate today = LocalDate.now();
    private int seq;
    private Long accountId;
    private Long cashAccountId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("detect-tester", null, List.of()));
        accountId = tx().execute(s -> {
            BankConnectionEntity c = new BankConnectionEntity();
            c.setProvider(FeedProvider.STUB);
            c.setProviderMode(SaltEdgeMode.CLIENT);
            c.setBankName("Stub Bank");
            c.setExternalConnectionId("DET-CONN-" + runId);
            c.setStatus(BankConnectionStatus.ACTIVE);
            c = connectionDao.save(c);
            BankAccountEntity a = new BankAccountEntity();
            a.setType(BankAccountType.BANK);
            a.setConnection(c);
            a.setExternalId("DET-ACC-" + runId);
            a.setName("Main " + runId);
            a.setCurrency("PLN");
            return accountDao.save(a).getId();
        });
        cashAccountId = tx().execute(s -> {
            BankAccountEntity cash = new BankAccountEntity();
            cash.setType(BankAccountType.CASH);
            cash.setName("Kasa " + runId);
            cash.setCurrency("PLN");
            return accountDao.save(cash).getId();
        });
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // --- detection ------------------------------------------------------------------------------

    @Test
    @DisplayName("R14.5 / R14.6 / R14.7 / R27.7: payout and invoice lines auto-link, a weak match is suggested, cash is skipped")
    void detectNewPostedLines() {
        BigDecimal payoutAmount = uniqueAmount();
        String workerName = "Detekcja Pracownik " + runId;
        Long payoutId = tx().execute(s -> newPayout(workerName, payoutAmount).getId());
        BigDecimal invoiceTotal = uniqueAmount();
        String invoiceNo = "FV/" + runId + "/1";
        Long invoiceDocId = tx().execute(s -> newDocument(invoiceTotal, invoiceNo).getId());
        BigDecimal weakTotal = uniqueAmount();
        Long weakDocId = tx().execute(s -> newDocument(weakTotal, "WEAK-" + runId).getId());

        Long payoutLine = newLine(payoutAmount.negate(), "Wynagrodzenie", workerName);
        Long invoiceLine = newLine(invoiceTotal.negate(), "Zaplata za " + invoiceNo, "Hurtownia " + runId);
        Long weakLine = newLine(weakTotal.negate(), "Przelew " + runId, "Nieznany " + runId);
        Long cashLine = tx().execute(s -> cashService.createManual(new CashEntryRequest(cashAccountId, CashDirection.EXPENSE,
                uniqueAmount(), today, "Kasa " + runId, "gotowka", null)).transactionId());

        DetectionSummary summary = detectionService.detect(List.of(payoutLine, invoiceLine, weakLine, cashLine), null);

        assertThat(summary.processed()).isEqualTo(3);
        assertThat(summary.autoLinked()).isEqualTo(2);
        assertThat(summary.suggested()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.toReview()).as("AUTO_LINKED lines are not reviewed yet").isEqualTo(3);
        assertThat(summary.aiQueued()).as("AI disabled in tests").isZero();

        tx().executeWithoutResult(s -> {
            assertThat(status(payoutLine)).isEqualTo(LinkStatus.AUTO_LINKED);
            assertThat(linkDao.findByTransactionIdOrderByIdAsc(payoutLine)).singleElement().satisfies(l -> {
                assertThat(l.getKind()).isEqualTo(LinkKind.WORKER_PAYOUT);
                assertThat(l.getSource()).isEqualTo(DetectionSource.RULE);
                assertThat(l.getConfirmedAt()).isNull();
                assertThat(l.getWorkerPayout().getId()).isEqualTo(payoutId);
            });
            WorkerPayoutEntity payout = payoutDao.findById(payoutId).orElseThrow();
            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);

            assertThat(status(invoiceLine)).isEqualTo(LinkStatus.AUTO_LINKED);
            assertThat(linkDao.findByTransactionIdOrderByIdAsc(invoiceLine)).singleElement()
                    .satisfies(l -> assertThat(l.getProcurementDocument().getId()).isEqualTo(invoiceDocId));

            assertThat(status(weakLine)).isEqualTo(LinkStatus.SUGGESTED);
            assertThat(openSuggestions(weakLine)).singleElement().satisfies(sg -> {
                assertThat(sg.getSource()).isEqualTo(DetectionSource.RULE);
                assertThat(sg.getProcurementDocument().getId()).isEqualTo(weakDocId);
            });
            assertThat(linkDao.findByTransactionIdOrderByIdAsc(weakLine)).isEmpty();

            assertThat(transactionDao.findById(cashLine).orElseThrow().getLinkStatus()).isEqualTo(LinkStatus.UNLINKED);
        });
    }

    @Test
    @DisplayName("R17.6: re-run supersedes the open suggestions and stores fresh ones")
    void rerunDetection() {
        BigDecimal total = uniqueAmount();
        Long docId = tx().execute(s -> newDocument(total, "RR-" + runId).getId());
        Long line = newLine(total.negate(), "Przelew " + runId, "Nieznany " + runId);
        detectionService.detect(List.of(line), null);
        Long firstSuggestion = tx().execute(s -> openSuggestions(line).get(0).getId());

        DetectionSummary summary = detectionService.rerunDetection(line, false);

        assertThat(summary.processed()).isEqualTo(1);
        tx().executeWithoutResult(s -> {
            assertThat(suggestionDao.findById(firstSuggestion).orElseThrow().getStatus())
                    .isEqualTo(SuggestionStatus.SUPERSEDED);
            assertThat(openSuggestions(line)).singleElement()
                    .satisfies(sg -> assertThat(sg.getProcurementDocument().getId()).isEqualTo(docId));
            assertThat(status(line)).isEqualTo(LinkStatus.SUGGESTED);
        });
    }

    // --- reverse document matching --------------------------------------------------------------

    @Test
    @DisplayName("R28.2: a parsed UNMATCHED document auto-links the existing line; a linked document is skipped")
    void reverseMatchingAutoLinks() {
        BigDecimal total = uniqueAmount();
        String invoiceNo = "FV/" + runId + "/R";
        Long line = newLine(total.negate(), "Faktura " + invoiceNo, "Dostawca " + runId);
        Long docId = tx().execute(s -> newDocument(total, invoiceNo).getId());

        ReverseDocumentMatcher.Result result = reverseMatcher.matchDocument(docId);

        assertThat(result.outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.AUTO_LINKED);
        assertThat(result.transactionId()).isEqualTo(line);
        tx().executeWithoutResult(s -> {
            assertThat(status(line)).isEqualTo(LinkStatus.AUTO_LINKED);
            assertThat(linkDao.findByProcurementDocumentId(docId)).singleElement().satisfies(l -> {
                assertThat(l.getTransaction().getId()).isEqualTo(line);
                assertThat(l.getSource()).isEqualTo(DetectionSource.RULE);
                assertThat(l.getAmount()).isEqualByComparingTo(total);
            });
        });
        // Re-processing a now linked document (e.g. one linked from a ledger line) does nothing.
        assertThat(reverseMatcher.matchDocument(docId).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SKIPPED);
    }

    @Test
    @DisplayName("R28.2 / R28.3: a weak reverse match is suggested on the best line through the parsed event")
    void reverseMatchingSuggestsThroughEvent() throws InterruptedException {
        BigDecimal total = uniqueAmount();
        Long line = newLine(total.negate(), "Przelew " + runId, "Ktos " + runId);
        Long docId = tx().execute(s -> newDocument(total, "EV-" + runId).getId());

        tx().executeWithoutResult(s -> events.publishEvent(new ProcurementDocumentParsedEvent(docId)));

        long deadline = System.currentTimeMillis() + 15_000;
        List<BankTransactionSuggestionEntity> open = List.of();
        while (System.currentTimeMillis() < deadline) {
            open = tx().execute(s -> openSuggestions(line));
            if (!open.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        List<BankTransactionSuggestionEntity> found = open;
        tx().executeWithoutResult(s -> {
            assertThat(found).singleElement().satisfies(sg -> {
                assertThat(sg.getKind()).isEqualTo(LinkKind.PROCUREMENT);
                assertThat(sg.getProcurementDocument().getId()).isEqualTo(docId);
            });
            assertThat(status(line)).isEqualTo(LinkStatus.SUGGESTED);
            assertThat(linkDao.findByProcurementDocumentId(docId)).isEmpty();
        });
        // A second parse of the same document does not duplicate the suggestion.
        assertThat(reverseMatcher.matchDocument(docId).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SUGGESTED);
        List<BankTransactionSuggestionEntity> afterSecondParse = tx().execute(s -> openSuggestions(line));
        assertThat(afterSecondParse).hasSize(1);
    }

    // --- helpers --------------------------------------------------------------------------------

    private LinkStatus status(Long id) {
        return transactionDao.findById(id).orElseThrow().getLinkStatus();
    }

    private List<BankTransactionSuggestionEntity> openSuggestions(Long id) {
        List<BankTransactionSuggestionEntity> list =
                suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(id, SuggestionStatus.OPEN);
        list.forEach(sg -> {
            if (sg.getProcurementDocument() != null) {
                sg.getProcurementDocument().getId();
            }
        });
        return list;
    }

    /** A random amount in 1000.00..9999.99 that no other test of the run uses. */
    private BigDecimal uniqueAmount() {
        return BigDecimal.valueOf(100_000 + random.nextInt(899_999), 2);
    }

    private Long newLine(BigDecimal amount, String title, String counterparty) {
        int n = ++seq;
        return tx().execute(s -> {
            BankTransactionEntity t = new BankTransactionEntity();
            t.setAccount(accountDao.findById(accountId).orElseThrow());
            t.setChannel(LedgerChannel.BANK);
            t.setOrigin(LedgerOrigin.BANK_FEED);
            t.setExternalId("DET-" + runId + "-" + n);
            t.setStatus(BankTxnStatus.POSTED);
            t.setAmount(amount);
            t.setCurrency("PLN");
            t.setOperationDate(today);
            t.setBookingDate(today);
            t.setTitle(title);
            t.setCounterpartyName(counterparty);
            t.setCounterpartyKey("NAME:" + counterparty.toUpperCase(Locale.ROOT));
            return transactionDao.save(t).getId();
        });
    }

    private UserEntity newUser(String name) {
        int n = ++seq;
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail("detect+" + runId.toLowerCase(Locale.ROOT) + "-" + n + "@example.com");
        user.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    private WorkerPayoutEntity newPayout(String workerName, BigDecimal amount) {
        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setWorker(newUser(workerName));
        payout.setPayoutDate(today);
        payout.setPeriodFrom(today.minusDays(7));
        payout.setPeriodTo(today.minusDays(1));
        payout.setAmount(amount);
        payout.setSuggestedAmount(amount);
        payout.setPaymentChannel(PaymentChannel.BANK_TRANSFER);
        payout.setStatus(PayoutStatus.PLANNED);
        return payoutDao.save(payout);
    }

    private ProcurementDocumentEntity newDocument(BigDecimal total, String invoiceNumber) {
        int n = ++seq;
        ProjectEntity project = new ProjectEntity();
        project.setName("Detect project " + runId + "-" + n);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectDao.save(project);
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(project);
        document.setUploadedBy(newUser("Uploader " + runId + "-" + n));
        document.setRawObjectKey("raw/" + runId + "/" + n);
        document.setSourceMimeType("application/pdf");
        document.setStatus(ProcessingStatus.PARSED);
        document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
        document.setDocumentTotal(total);
        document.setInvoiceNumber(invoiceNumber);
        document.setInvoiceDate(today);
        document.setSupplierName("Supplier " + runId + "-" + n);
        return documentDao.save(document);
    }
}
