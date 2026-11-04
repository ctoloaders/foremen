package com.foremen.service.bank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.cash.CashAccountRequest;
import com.foremen.service.bank.cash.CashDirection;
import com.foremen.service.bank.cash.CashEntryRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerDetail;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerPage;
import com.foremen.service.bank.ledger.LedgerDtos.LineCandidate;
import com.foremen.service.bank.ledger.LedgerDtos.LinkTarget;
import com.foremen.service.bank.ledger.LedgerDtos.TargetType;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerDirection;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerStatusFilter;
import com.foremen.service.bank.link.BankLinkService;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 7.2 (read side) — {@link BankLedgerQueryService} against the real schema (Liquibase on
 * Testcontainers PostgreSQL): filtered page + per-currency totals, detail with the ADMIN-only provider
 * payload, link-target pickers and the reverse "lines for a document" picker.
 *
 * <p>Repeatability: fresh container per run; every test works on its own cash account with a per-run
 * pseudo currency and per-run names, and filters by that account.
 *
 * <p>Validates: Requirements 13, 17.2, 20.2, 20.3, 20.4, 20.5, 21.6, 28.1
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 7.2: ledger query service")
class BankLedgerQueryServiceIntegrationTest {

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

    @Autowired private BankLedgerQueryService queryService;
    @Autowired private CashLedgerService cashService;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private int seq;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ledger-tester", null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (ForemenApiException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected ForemenApiException");
    }

    @Test
    @DisplayName("list: filters (account, direction, TO_REVIEW, q, amount, transactionId) and per-currency totals")
    void listFiltersAndTotals() {
        Fixture fx = fixture();
        LedgerFilter byAccount = filter(fx.accountId(), null, List.of(), null, null, null);

        LedgerPage page = queryService.list(byAccount, PageRequest.of(0, 50));
        assertThat(page.content()).hasSize(3);
        assertThat(page.page().totalElements()).isEqualTo(3);
        assertThat(page.totals().count()).isEqualTo(3);
        assertThat(page.totals().toReview()).isEqualTo(3);
        assertThat(page.totals().income()).isEqualByComparingTo("100.00");
        assertThat(page.totals().expense()).isEqualByComparingTo("50.00");
        assertThat(page.totals().net()).isEqualByComparingTo("50.00");
        assertThat(page.totals().byCurrency()).singleElement()
                .satisfies(c -> assertThat(c.currency()).isEqualTo(fx.currency()));
        assertThat(page.content()).allSatisfy(r -> {
            assertThat(r.accountId()).isEqualTo(fx.accountId());
            assertThat(r.linkStatus()).isEqualTo(LinkStatus.UNLINKED);
            assertThat(r.editable()).isTrue();
        });

        // Totals of a 1-row page still cover the whole filter.
        LedgerPage small = queryService.list(byAccount, PageRequest.of(0, 1));
        assertThat(small.content()).hasSize(1);
        assertThat(small.totals().count()).isEqualTo(3);
        assertThat(small.totals().net()).isEqualByComparingTo("50.00");

        assertThat(queryService.list(filter(fx.accountId(), LedgerDirection.EXPENSE, List.of(), null, null, null),
                null).content()).hasSize(2).allSatisfy(r -> assertThat(r.direction()).isEqualTo(LedgerDirection.EXPENSE));
        assertThat(queryService.list(filter(fx.accountId(), null, List.of(LedgerStatusFilter.TO_REVIEW), null, null,
                null), null).content()).hasSize(3);
        assertThat(queryService.list(filter(fx.accountId(), null, List.of(LedgerStatusFilter.LINKED), null, null,
                null), null).content()).isEmpty();
        assertThat(queryService.list(filter(fx.accountId(), null, List.of(), "shop " + runId.toLowerCase(Locale.ROOT),
                null, null), null).content()).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(fx.shopLineId()));
        assertThat(queryService.list(filter(fx.accountId(), null, List.of(), null, new BigDecimal("25"), null),
                null).content()).hasSize(2);
        assertThat(queryService.list(filter(fx.accountId(), null, List.of(), null, null, fx.shopLineId()), null)
                .content()).singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("-30.00"));
    }

    @Test
    @DisplayName("detail: account names, remaining amount; provider payload only with the ADMIN flag")
    void detailProviderPayloadOnlyForAdmin() {
        Fixture fx = fixture();
        tx().executeWithoutResult(s -> {
            BankTransactionEntity t = transactionDao.findById(fx.shopLineId()).orElseThrow();
            t.setProviderPayload(Map.of("raw", "payload-" + runId));
            transactionDao.save(t);
        });

        LedgerDetail plain = queryService.detail(fx.shopLineId(), false);
        assertThat(plain.providerPayload()).isNull();
        assertThat(plain.line().accountName()).isEqualTo("Kasa " + runId);
        assertThat(plain.line().links()).isEmpty();
        assertThat(plain.suggestions()).isEmpty();
        assertThat(plain.linkedAmount()).isEqualByComparingTo("0");
        assertThat(plain.remainingAmount()).isEqualByComparingTo("30.00");

        LedgerDetail admin = queryService.detail(fx.shopLineId(), true);
        assertThat(admin.providerPayload()).containsEntry("raw", "payload-" + runId);

        assertThat(code(() -> queryService.detail(Long.MAX_VALUE, false))).isEqualTo(BankLedgerQueryService.NOT_FOUND);
    }

    @Test
    @DisplayName("link targets: documents with payment status, own accounts excluded, kind/direction enforced")
    void linkTargets() {
        Fixture fx = fixture();
        Long docId = tx().execute(s -> newDocument(new BigDecimal("30.00")).getId());

        List<LinkTarget> docs = queryService.linkTargets(fx.shopLineId(), LinkKind.PROCUREMENT, runId, null, null);
        assertThat(docs).anySatisfy(t -> {
            assertThat(t.targetType()).isEqualTo(TargetType.DOCUMENT);
            assertThat(t.id()).isEqualTo(docId);
            assertThat(t.paymentStatus()).isEqualTo(DocumentPaymentStatus.UNMATCHED);
            assertThat(t.remaining()).isEqualByComparingTo("30.00");
        });

        Fixture other = fixture();
        List<LinkTarget> accounts = queryService.linkTargets(fx.shopLineId(), LinkKind.INTERNAL_TRANSFER,
                "Kasa " + runId, null, null);
        assertThat(accounts).extracting(LinkTarget::id).contains(other.accountId()).doesNotContain(fx.accountId());

        assertThat(code(() -> queryService.linkTargets(fx.shopLineId(), LinkKind.CLIENT_PAYMENT, null, null, null)))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
    }

    @Test
    @DisplayName("link candidates: unlinked expense lines for a document, the matching one ranked with a score")
    void linkCandidatesForDocument() {
        Fixture fx = fixture();
        Long docId = tx().execute(s -> newDocument(new BigDecimal("30.00")).getId());

        List<LineCandidate> candidates = queryService.linkCandidates(docId);
        assertThat(candidates).extracting(c -> c.line().id()).contains(fx.shopLineId())
                .doesNotContain(fx.incomeLineId());
        LineCandidate shop = candidates.stream().filter(c -> c.line().id().equals(fx.shopLineId())).findFirst()
                .orElseThrow();
        assertThat(shop.remaining()).isEqualByComparingTo("30.00");
        assertThat(shop.score()).isPositive();

        assertThat(code(() -> queryService.linkCandidates(Long.MAX_VALUE))).isEqualTo(BankLedgerQueryService.NOT_FOUND);
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** A fresh cash account with +100 income, −30 "Shop {runId}" expense and −20 expense, all unlinked. */
    private Fixture fixture() {
        int n = ++seq;
        String currency = pseudoCurrency(n);
        Long accountId = tx().execute(s -> cashService.createAccount(
                new CashAccountRequest("Kasa " + runId + (n == 1 ? "" : "-" + n), currency, null, false, false)).id());
        LocalDate today = LocalDate.now();
        Long income = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId, CashDirection.INCOME,
                new BigDecimal("100.00"), today, "Client " + runId, "Wpłata " + runId, null)).transactionId());
        Long shop = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId, CashDirection.EXPENSE,
                new BigDecimal("30.00"), today, "Shop " + runId, "Faktura FV/" + runId, null)).transactionId());
        tx().executeWithoutResult(s -> cashService.createManual(new CashEntryRequest(accountId, CashDirection.EXPENSE,
                new BigDecimal("20.00"), today, "Kiosk", "Drobne", null)));
        return new Fixture(accountId, currency, income, shop);
    }

    private record Fixture(Long accountId, String currency, Long incomeLineId, Long shopLineId) {
    }

    private LedgerFilter filter(Long accountId, LedgerDirection direction, List<LedgerStatusFilter> statuses,
                                String q, BigDecimal amountMin, Long transactionId) {
        return new LedgerFilter(null, null, null, direction, null, accountId, statuses, null, null, null, amountMin,
                null, q, null, transactionId);
    }

    private ProcurementDocumentEntity newDocument(BigDecimal total) {
        int n = ++seq;
        ProjectEntity project = new ProjectEntity();
        project.setName("Ledger project " + runId + "-" + n);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectDao.save(project);
        UserEntity user = new UserEntity();
        user.setName("Ledger user " + runId + "-" + n);
        user.setEmail("ledger+" + runId.toLowerCase(Locale.ROOT) + "-" + n + "@example.com");
        user.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        user.setActive(true);
        user = userDao.save(user);
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(project);
        document.setUploadedBy(user);
        document.setRawObjectKey("raw/" + runId + "/" + n);
        document.setSourceMimeType("application/pdf");
        document.setStatus(ProcessingStatus.PARSED);
        document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
        document.setInvoiceDate(LocalDate.now());
        document.setDocumentTotal(total);
        document.setSupplierName("Shop " + runId);
        document.setInvoiceNumber("FV/" + runId);
        return documentDao.save(document);
    }

    /** A per-run, per-fixture 3-letter pseudo currency so fixtures never share totals. */
    private String pseudoCurrency(int n) {
        int h = Math.floorMod(runId.hashCode() + n * 31, 26 * 26);
        return "Q" + (char) ('A' + h / 26) + (char) ('A' + h % 26);
    }
}
