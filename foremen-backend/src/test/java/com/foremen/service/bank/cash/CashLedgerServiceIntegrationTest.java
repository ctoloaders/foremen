package com.foremen.service.bank.cash;

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
import org.springframework.dao.DataIntegrityViolationException;
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
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SystemSource;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.TransactionStatus;
import com.foremen.service.payroll.WorkerPayoutPaidEvent;
import com.foremen.service.payroll.WorkerPayoutService;
import com.foremen.service.procurement.ProcurementCashPayment;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 6.12 — {@link CashLedgerService}, {@link PayoutCashHook} and {@link ProcurementCashHook}
 * against the real schema (Liquibase changelog on Testcontainers PostgreSQL), so the cash CHECKs and the
 * partial UNIQUE indexes ({@code (system_source, system_source_id)}, one default cash register per
 * currency) are exercised.
 *
 * <p>Repeatability: a fresh container per run; users / documents carry a per-run id and the cash-account
 * CRUD test uses a per-run pseudo currency. The PLN default register is shared on purpose (payroll and
 * procurement are PLN-only).
 *
 * <p>Validates: Requirements 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 27.7, 28.1
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 6.12: cash ledger")
class CashLedgerServiceIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final int TRIALS = 12;

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

    @Autowired private CashLedgerService cashService;
    @Autowired private ProcurementCashHook procurementHook;
    @Autowired private WorkerPayoutService payoutService;
    @Autowired private BankLinkService linkService;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private PaymentTransactionDao paymentTransactionDao;
    @Autowired private PaymentScheduleDao scheduleDao;
    @Autowired private WorkerPayoutDao payoutDao;
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
                new UsernamePasswordAuthenticationToken("cash-tester", null, List.of()));
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

    // --- Property 11 ----------------------------------------------------------------------------

    /**
     * Property 11 (one cash entry per source): over random payouts (channel CASH / BANK_TRANSFER, paid in
     * payroll or from a ledger line) and random event re-deliveries, a payout has exactly one SYSTEM entry
     * iff it was paid in cash in payroll, and none otherwise.
     *
     * <p>Validates: Requirements 27.4, 27.5
     */
    @Test
    @DisplayName("Property 11: payouts produce exactly one SYSTEM entry iff CASH and not from the ledger")
    void property11OneEntryPerPayout() {
        for (int trial = 0; trial < TRIALS; trial++) {
            long seed = 0x6_12_01L * 1000 + trial;
            Random random = new Random(seed);
            boolean cash = random.nextBoolean();
            boolean fromLedger = random.nextInt(3) == 0;
            int redeliveries = random.nextInt(3);
            BigDecimal amount = BigDecimal.valueOf(10 + random.nextInt(900)).setScale(2);
            Long payoutId = tx().execute(s -> newPayout(amount, cash ? PaymentChannel.CASH
                    : PaymentChannel.BANK_TRANSFER).getId());

            if (fromLedger) {
                // Paid from a MANUAL cash line (cash) or a manual link on that line — LinkEffects marks it
                // paid with fromLedger = true.
                tx().executeWithoutResult(s -> cashService.createManual(new CashEntryRequest(null,
                        CashDirection.EXPENSE, amount, LocalDate.now(), "Worker", "ledger pay",
                        LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payoutId))));
            } else {
                tx().executeWithoutResult(s -> payoutService.markPaid(payoutId));
            }
            for (int i = 0; i < redeliveries; i++) {
                tx().executeWithoutResult(s -> {
                    WorkerPayoutEntity p = payoutDao.findById(payoutId).orElseThrow();
                    events.publishEvent(new WorkerPayoutPaidEvent(p.getId(), p.getPaymentChannel(), p.getPaidAt(),
                            p.getAmount(), "PLN", "Worker", p.getNote(), fromLedger));
                });
            }

            boolean expectEntry = cash && !fromLedger;
            tx().executeWithoutResult(s -> {
                WorkerPayoutEntity p = payoutDao.findById(payoutId).orElseThrow();
                assertThat(p.getStatus()).as("seed %d", seed).isEqualTo(PayoutStatus.PAID);
                var entry = transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, payoutId);
                assertThat(entry.isPresent()).as("seed %d cash=%s fromLedger=%s", seed, cash, fromLedger)
                        .isEqualTo(expectEntry);
                List<BankTransactionLinkEntity> links = linkDao.findByWorkerPayoutId(payoutId);
                if (expectEntry) {
                    BankTransactionEntity t = entry.get();
                    assertThat(t.getChannel()).isEqualTo(LedgerChannel.CASH);
                    assertThat(t.getOrigin()).isEqualTo(LedgerOrigin.SYSTEM);
                    assertThat(t.getStatus()).isEqualTo(BankTxnStatus.POSTED);
                    assertThat(t.getAmount()).isEqualByComparingTo(amount.negate());
                    assertThat(t.getLinkStatus()).isEqualTo(LinkStatus.LINKED);
                    assertThat(t.getAccount().isDefaultForCash()).isTrue();
                    assertThat(t.getAccount().getCurrency()).isEqualTo("PLN");
                    assertThat(links).singleElement().satisfies(l -> {
                        assertThat(l.getTransaction().getId()).isEqualTo(t.getId());
                        assertThat(l.getAmount()).isEqualByComparingTo(amount);
                        assertThat(l.getSource()).isEqualTo(DetectionSource.MANUAL);
                        assertThat(l.getConfirmedAt()).isNotNull();
                    });
                } else if (fromLedger) {
                    // Exactly the one MANUAL ledger line that paid it.
                    assertThat(links).singleElement().satisfies(l ->
                            assertThat(l.getTransaction().getOrigin()).isEqualTo(LedgerOrigin.MANUAL));
                    assertThat(p.getPaymentChannel()).isEqualTo(PaymentChannel.CASH);
                } else {
                    assertThat(links).as("BANK_TRANSFER payout stays an open candidate").isEmpty();
                }
            });
        }
    }

    /**
     * Property 11 (procurement): one SYSTEM entry per document — a second cash payment is refused and the
     * partial UNIQUE index rejects a duplicate row written past the service.
     *
     * <p>Validates: Requirements 28.1, 27.2
     */
    @Test
    @DisplayName("Property 11: a procurement document gets at most one SYSTEM cash entry")
    void property11OneEntryPerDocument() {
        Long docId = tx().execute(s -> newDocument(null).getId());
        tx().executeWithoutResult(s -> procurementHook.payInCash(
                new ProcurementCashPayment(docId, null, new BigDecimal("123.45"), LocalDate.of(2026, 2, 3))));

        assertThat(code(() -> tx().executeWithoutResult(s -> procurementHook.payInCash(
                new ProcurementCashPayment(docId, null, new BigDecimal("5.00"), LocalDate.now())))))
                .isEqualTo(CashLedgerService.ALREADY_RECORDED);

        BankTransactionEntity entry = tx().execute(s -> {
            BankTransactionEntity t = transactionDao
                    .findBySystemSourceAndSystemSourceId(SystemSource.PROCUREMENT_CASH, docId).orElseThrow();
            t.getAccount().getId();
            return t;
        });
        assertThat(entry.getAmount()).isEqualByComparingTo("-123.45");
        assertThat(entry.getBookingDate()).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(entry.getCounterpartyName()).isEqualTo("Castorama " + runId);
        assertThat(entry.getLinkStatus()).isEqualTo(LinkStatus.LINKED);
        tx().executeWithoutResult(s -> assertThat(linkDao.findByProcurementDocumentId(docId)).singleElement()
                .satisfies(l -> {
                    assertThat(l.getKind()).isEqualTo(LinkKind.PROCUREMENT);
                    assertThat(l.getAmount()).isEqualByComparingTo("123.45");
                }));

        // DB backstop: a duplicate (system_source, system_source_id) row is rejected.
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            BankAccountEntity account = accountDao.findById(entry.getAccount().getId()).orElseThrow();
            BankTransactionEntity dup = new BankTransactionEntity();
            dup.setAccount(account);
            dup.setChannel(LedgerChannel.CASH);
            dup.setOrigin(LedgerOrigin.SYSTEM);
            dup.setSystemSource(SystemSource.PROCUREMENT_CASH);
            dup.setSystemSourceId(docId);
            dup.setStatus(BankTxnStatus.POSTED);
            dup.setAmount(new BigDecimal("-1.00"));
            dup.setCurrency("PLN");
            dup.setOperationDate(LocalDate.now());
            dup.setBookingDate(LocalDate.now());
            transactionDao.save(dup);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("procurement cash: known total caps the link, explicit account used, archived account refused")
    void procurementCashAccountAndTotal() {
        String currency = "PLN";
        Long accountId = tx().execute(s -> cashService.createAccount(
                new CashAccountRequest("Kasa budowa " + runId, currency, null, false, false)).id());
        Long docId = tx().execute(s -> newDocument(new BigDecimal("100.00")).getId());
        tx().executeWithoutResult(s -> procurementHook.payInCash(
                new ProcurementCashPayment(docId, accountId, new BigDecimal("120.00"), LocalDate.now())));
        tx().executeWithoutResult(s -> {
            BankTransactionEntity t = transactionDao
                    .findBySystemSourceAndSystemSourceId(SystemSource.PROCUREMENT_CASH, docId).orElseThrow();
            assertThat(t.getAccount().getId()).isEqualTo(accountId);
            assertThat(t.getLinkStatus()).isEqualTo(LinkStatus.PARTIALLY_LINKED);
            assertThat(linkDao.sumAmountByProcurementDocumentId(docId)).isEqualByComparingTo("100.00");
        });

        tx().executeWithoutResult(s -> cashService.archiveAccount(accountId));
        Long doc2 = tx().execute(s -> newDocument(null).getId());
        assertThat(code(() -> tx().executeWithoutResult(s -> procurementHook.payInCash(
                new ProcurementCashPayment(doc2, accountId, new BigDecimal("10.00"), LocalDate.now())))))
                .isEqualTo(CashLedgerService.ACCOUNT_ARCHIVED);
        assertThat(transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PROCUREMENT_CASH, doc2)).isEmpty();
    }

    @Test
    @DisplayName("cash payout with no default PLN register auto-creates 'Cash PLN' flagged default")
    void payoutAutoCreatesDefaultCashAccount() {
        tx().executeWithoutResult(s -> accountDao.findByTypeOrderByIdAsc(BankAccountType.CASH).stream()
                .filter(a -> "PLN".equals(a.getCurrency()) && a.isDefaultForCash())
                .forEach(a -> {
                    a.setDefaultForCash(false);
                    accountDao.save(a);
                }));
        java.util.Optional<BankAccountEntity> noDefault = tx().execute(s -> accountDao.findDefaultCashAccount("PLN"));
        assertThat(noDefault).isEmpty();

        Long payoutId = tx().execute(s -> newPayout(new BigDecimal("250.00"), PaymentChannel.CASH).getId());
        tx().executeWithoutResult(s -> payoutService.markPaid(payoutId));

        tx().executeWithoutResult(s -> {
            BankAccountEntity def = accountDao.findDefaultCashAccount("PLN").orElseThrow();
            assertThat(def.getName()).isEqualTo("Cash PLN");
            BankTransactionEntity t = transactionDao
                    .findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, payoutId).orElseThrow();
            assertThat(t.getAccount().getId()).isEqualTo(def.getId());
            assertThat(t.getCounterpartyName()).startsWith("Cash worker");
            assertThat(t.getTitle()).isNotBlank();
        });
    }

    // --- Cash accounts --------------------------------------------------------------------------

    @Test
    @DisplayName("cash accounts: one default per currency, archive clears default, computed balance")
    void cashAccountCrudAndBalance() {
        String cur = pseudoCurrency();
        CashAccountDto a = tx().execute(s -> cashService.createAccount(
                new CashAccountRequest("Kasa A " + runId, cur, new BigDecimal("100.00"), true, false)));
        CashAccountDto b = tx().execute(s -> cashService.createAccount(
                new CashAccountRequest("Kasa B " + runId, cur, null, true, false)));
        assertThat(tx().execute(s -> cashService.getAccount(a.id())).defaultForCash()).isFalse();
        assertThat(b.defaultForCash()).isTrue();

        tx().executeWithoutResult(s -> cashService.updateAccount(a.id(),
                new CashAccountRequest(null, null, null, true, null)));
        assertThat(tx().execute(s -> cashService.getAccount(b.id())).defaultForCash()).isFalse();
        assertThat(tx().execute(s -> accountDao.findDefaultCashAccount(cur)).orElseThrow().getId()).isEqualTo(a.id());

        tx().executeWithoutResult(s -> {
            cashService.createManual(new CashEntryRequest(a.id(), CashDirection.INCOME, new BigDecimal("50.00"),
                    LocalDate.now(), null, "in", null));
            cashService.createManual(new CashEntryRequest(a.id(), CashDirection.EXPENSE, new BigDecimal("30.25"),
                    LocalDate.now(), null, "out", null));
        });
        CashAccountDto reloaded = tx().execute(s -> cashService.getAccount(a.id()));
        assertThat(reloaded.balance()).isEqualByComparingTo("119.75");
        List<CashAccountDto> all = tx().execute(s -> cashService.listAccounts());
        assertThat(all)
                .filteredOn(d -> d.id().equals(a.id())).singleElement()
                .satisfies(d -> assertThat(d.balance()).isEqualByComparingTo("119.75"));

        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateAccount(a.id(),
                new CashAccountRequest(null, "EUR", null, null, null)))))
                .isEqualTo(CashLedgerService.CURRENCY_LOCKED);

        CashAccountDto archived = tx().execute(s -> cashService.archiveAccount(a.id()));
        assertThat(archived.archived()).isTrue();
        assertThat(archived.defaultForCash()).isFalse();
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.createManual(new CashEntryRequest(a.id(),
                CashDirection.INCOME, BigDecimal.ONE, LocalDate.now(), null, null, null)))))
                .isEqualTo(CashLedgerService.ACCOUNT_ARCHIVED);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateAccount(a.id(),
                new CashAccountRequest(null, null, null, true, null)))))
                .isEqualTo(CashLedgerService.ACCOUNT_ARCHIVED);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.createAccount(
                new CashAccountRequest(" ", cur, null, false, false))))).isEqualTo(CashLedgerService.NAME_REQUIRED);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.createAccount(
                new CashAccountRequest("X", "zł", null, false, false))))).isEqualTo(CashLedgerService.CURRENCY_INVALID);
    }

    // --- Manual entries: create / edit / delete -------------------------------------------------

    @Test
    @DisplayName("manual entry: link in the same form → LINKED; without → UNLINKED; failing link rolls back")
    void manualEntryWithOptionalLink() {
        Long accountId = tx().execute(s -> cashService.defaultAccountOrCreate("PLN").getId());
        Long payoutId = tx().execute(s -> newPayout(new BigDecimal("80.00"), PaymentChannel.BANK_TRANSFER).getId());

        CashEntryOutcome linked = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId,
                CashDirection.EXPENSE, new BigDecimal("80.00"), LocalDate.of(2026, 3, 1), "Jan", "wypłata",
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payoutId))));
        assertThat(linked.linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(linked.amount()).isEqualByComparingTo("-80.00");
        tx().executeWithoutResult(s -> {
            WorkerPayoutEntity p = payoutDao.findById(payoutId).orElseThrow();
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.PAID);
            assertThat(p.getPaymentChannel()).isEqualTo(PaymentChannel.CASH);
            BankTransactionEntity t = transactionDao.findById(linked.transactionId()).orElseThrow();
            assertThat(t.getOrigin()).isEqualTo(LedgerOrigin.MANUAL);
            assertThat(t.getCreatedByUser()).isEqualTo("cash-tester");
        });
        assertThat(transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, payoutId)).isEmpty();

        CashEntryOutcome plain = tx().execute(s -> cashService.createManual(new CashEntryRequest(null,
                CashDirection.INCOME, new BigDecimal("15.00"), LocalDate.now(), null, null, null)));
        assertThat(plain.linkStatus()).isEqualTo(LinkStatus.UNLINKED);
        assertThat(plain.accountId()).isEqualTo(accountId);

        long before = transactionDao.count();
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.createManual(new CashEntryRequest(accountId,
                CashDirection.INCOME, new BigDecimal("15.00"), LocalDate.now(), null, null,
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payoutId))))))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
        assertThat(transactionDao.count()).as("entry rolled back with its link").isEqualTo(before);

        assertThat(code(() -> cashService.createManual(new CashEntryRequest(accountId, null, BigDecimal.ONE,
                LocalDate.now(), null, null, null)))).isEqualTo(CashLedgerService.DIRECTION_REQUIRED);
        assertThat(code(() -> cashService.createManual(new CashEntryRequest(accountId, CashDirection.INCOME,
                BigDecimal.ZERO, LocalDate.now(), null, null, null)))).isEqualTo(CashLedgerService.AMOUNT_INVALID);
        assertThat(code(() -> cashService.createManual(new CashEntryRequest(accountId, CashDirection.INCOME,
                BigDecimal.ONE, null, null, null, null)))).isEqualTo(CashLedgerService.DATE_REQUIRED);
    }

    @Test
    @DisplayName("edit / delete: MANUAL without locked links only; SYSTEM read-only; delete keeps payout PAID")
    void editAndDeleteRules() {
        Long accountId = tx().execute(s -> cashService.defaultAccountOrCreate("PLN").getId());

        // Edit an unlinked manual entry.
        CashEntryOutcome e = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId,
                CashDirection.EXPENSE, new BigDecimal("40.00"), LocalDate.of(2026, 1, 1), null, "a", null)));
        CashEntryOutcome edited = tx().execute(s -> cashService.updateManual(e.transactionId(), new CashEntryRequest(
                null, CashDirection.INCOME, new BigDecimal("45.00"), LocalDate.of(2026, 1, 2), "Kowalski", "b", null)));
        assertThat(edited.amount()).isEqualByComparingTo("45.00");
        tx().executeWithoutResult(s -> {
            BankTransactionEntity t = transactionDao.findById(e.transactionId()).orElseThrow();
            assertThat(t.getBookingDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(t.getCounterpartyName()).isEqualTo("Kowalski");
            assertThat(t.getTitle()).isEqualTo("b");
        });

        // Linked entry: amount cannot drop below the linked sum, direction cannot flip; delete keeps PAID.
        Long payoutId = tx().execute(s -> newPayout(new BigDecimal("60.00"), PaymentChannel.CASH).getId());
        CashEntryOutcome linked = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId,
                CashDirection.EXPENSE, new BigDecimal("60.00"), LocalDate.now(), null, null,
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payoutId))));
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateManual(linked.transactionId(),
                new CashEntryRequest(null, null, new BigDecimal("50.00"), null, null, null, null)))))
                .isEqualTo(BankLinkService.OVER_ALLOCATED);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateManual(linked.transactionId(),
                new CashEntryRequest(null, CashDirection.INCOME, null, null, null, null, null)))))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
        CashEntryOutcome grown = tx().execute(s -> cashService.updateManual(linked.transactionId(),
                new CashEntryRequest(null, null, new BigDecimal("70.00"), null, null, null, null)));
        assertThat(grown.linkStatus()).isEqualTo(LinkStatus.PARTIALLY_LINKED);

        boolean staysPaid = Boolean.TRUE.equals(tx().execute(s -> cashService.deleteManual(linked.transactionId())));
        assertThat(staysPaid).isTrue();
        assertThat(transactionDao.findById(linked.transactionId())).isEmpty();
        tx().executeWithoutResult(s -> {
            assertThat(payoutDao.findById(payoutId).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
            assertThat(linkDao.findByWorkerPayoutId(payoutId)).isEmpty();
        });

        // Locked link → neither edit nor delete.
        CashEntryOutcome income = tx().execute(s -> cashService.createManual(new CashEntryRequest(accountId,
                CashDirection.INCOME, new BigDecimal("90.00"), LocalDate.now(), null, null,
                LinkRequest.of(LinkKind.OTHER_INCOME, null))));
        tx().executeWithoutResult(s -> {
            // Turn the link into a reconciled client payment (what LinkEffects stores for CLIENT_PAYMENT),
            // written directly so the test needs no published schedule / tranches.
            ProjectEntity project = new ProjectEntity();
            project.setName("Locked project " + runId);
            project.setStatus(ProjectStatus.ACTIVE);
            project = projectDao.save(project);
            PaymentScheduleEntity schedule = new PaymentScheduleEntity();
            schedule.setProject(project);
            schedule.setCurrency("PLN");
            schedule.setSource(ScheduleSource.MANUAL);
            schedule = scheduleDao.save(schedule);
            PaymentTransactionEntity pt = new PaymentTransactionEntity();
            pt.setSchedule(schedule);
            pt.setChannel(com.foremen.service.payment.PaymentChannel.MANUAL);
            pt.setAmountGross(new BigDecimal("90.00"));
            pt.setReceivedAt(OffsetDateTime.now(ZoneOffset.UTC));
            pt.setStatus(TransactionStatus.CONFIRMED);
            pt = paymentTransactionDao.save(pt);
            BankTransactionLinkEntity link = linkDao.findById(income.linkId()).orElseThrow();
            link.setKind(LinkKind.CLIENT_PAYMENT);
            link.setPaymentSchedule(schedule);
            link.setProject(project);
            link.setPaymentTransaction(pt);
            linkDao.save(link);
        });
        assertThat(linkService.hasLockedLinks(income.transactionId())).isTrue();
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateManual(income.transactionId(),
                new CashEntryRequest(null, null, null, null, null, "x", null)))))
                .isEqualTo(CashLedgerService.ENTRY_NOT_EDITABLE);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.deleteManual(income.transactionId()))))
                .isEqualTo(CashLedgerService.ENTRY_NOT_EDITABLE);
        assertThat(transactionDao.findById(income.transactionId())).isPresent();

        // SYSTEM entries are read-only.
        Long cashPayout = tx().execute(s -> newPayout(new BigDecimal("33.00"), PaymentChannel.CASH).getId());
        tx().executeWithoutResult(s -> payoutService.markPaid(cashPayout));
        Long systemId = transactionDao.findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, cashPayout)
                .orElseThrow().getId();
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.updateManual(systemId,
                new CashEntryRequest(null, null, null, null, null, "x", null)))))
                .isEqualTo(CashLedgerService.ENTRY_SYSTEM_READ_ONLY);
        assertThat(code(() -> tx().executeWithoutResult(s -> cashService.deleteManual(systemId))))
                .isEqualTo(CashLedgerService.ENTRY_SYSTEM_READ_ONLY);
    }

    // --- builders -------------------------------------------------------------------------------

    private UserEntity newWorker() {
        int n = ++seq;
        UserEntity user = new UserEntity();
        user.setName("Cash worker " + runId + "-" + n);
        user.setEmail("cash-worker+" + runId.toLowerCase(Locale.ROOT) + "-" + n + "@example.com");
        user.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    private WorkerPayoutEntity newPayout(BigDecimal amount, PaymentChannel channel) {
        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setWorker(newWorker());
        payout.setPayoutDate(LocalDate.now());
        payout.setPeriodFrom(LocalDate.now().minusDays(7));
        payout.setPeriodTo(LocalDate.now().minusDays(1));
        payout.setAmount(amount);
        payout.setSuggestedAmount(amount);
        payout.setPaymentChannel(channel);
        payout.setStatus(PayoutStatus.PLANNED);
        return payoutDao.save(payout);
    }

    private ProcurementDocumentEntity newDocument(BigDecimal total) {
        int n = ++seq;
        ProjectEntity project = new ProjectEntity();
        project.setName("Cash project " + runId + "-" + n);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectDao.save(project);
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(project);
        document.setUploadedBy(newWorker());
        document.setRawObjectKey("raw/" + runId + "/" + n);
        document.setSourceMimeType("application/pdf");
        document.setStatus(ProcessingStatus.PARSED);
        document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
        document.setDocumentTotal(total);
        document.setSupplierName("Castorama " + runId);
        return documentDao.save(document);
    }

    /** A per-run 3-letter pseudo currency so the one-default-cash-per-currency index never collides. */
    private String pseudoCurrency() {
        char a = (char) ('A' + Math.floorMod(runId.hashCode(), 26));
        char b = (char) ('A' + Math.floorMod(runId.hashCode() / 26, 26));
        return "Q" + a + b;
    }
}
