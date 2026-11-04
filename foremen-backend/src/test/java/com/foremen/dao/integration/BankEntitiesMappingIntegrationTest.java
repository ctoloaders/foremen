package com.foremen.dao.integration;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankConnectionSecretDao;
import com.foremen.dao.BankCounterpartyRuleDao;
import com.foremen.dao.BankLinkedAmount;
import com.foremen.dao.BankSyncRunAccountDao;
import com.foremen.dao.BankSyncRunDao;
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
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionSecretEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.BankSyncRunAccountEntity;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.HolderMatch;
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
import com.foremen.dao.model.RuleOrigin;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.dao.model.SystemSource;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 3.1 — validates the bank-ledger JPA mapping against the real schema of changesets
 * {@code 201}–{@code 204}.
 *
 * <p>The full Liquibase changelog is applied to a Testcontainers PostgreSQL before the Spring context
 * starts, and the context boots with {@code ddl-auto=validate}, so Hibernate checks every mapped
 * column (names, types incl. {@code CHAR(3)}, JSONB, BYTEA, quoted {@code "trigger"}) against the
 * migrated tables. The tests then persist and read back one row of each bank entity (respecting the
 * DB CHECKs) and exercise the DAO lookups the services rely on.
 *
 * <p>Repeatability: every run gets a fresh container; all codes / emails / external ids / the cash
 * currency carry a per-run id, so re-running against the same DB would not collide.
 *
 * <p>Validates: Requirements 1.1, 2.1, 3.1, 3.7, 5.2, 6.1, 7.2, 13.1, 13.7, 17.3, 18.1, 27.1, 27.2
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider="
                + "com.foremen.dao.integration.BankEntitiesMappingIntegrationTest$BankTablesSchemaFilterProvider",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 3.1: entity mapping vs schema 201-204")
class BankEntitiesMappingIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    /** The tables owned / changed by changesets 201–204 — the scope of {@code ddl-auto=validate}. */
    private static final Set<String> VALIDATED_TABLES = Set.of(
            "bank_connections", "bank_connection_secrets", "bank_accounts", "bank_balance_gaps",
            "bank_sync_runs", "bank_sync_run_accounts", "bank_transactions",
            "bank_transaction_links", "bank_transaction_suggestions", "bank_counterparty_rules",
            "procurement_documents");

    /**
     * Restricts Hibernate schema validation to {@link #VALIDATED_TABLES}: the test classpath also maps
     * test-only entities without a Liquibase table, which would otherwise fail a global validate.
     */
    public static class BankTablesSchemaFilterProvider implements SchemaFilterProvider {

        private static final SchemaFilter BANK_TABLES = new SchemaFilter() {
            @Override
            public boolean includeNamespace(Namespace namespace) {
                return true;
            }

            @Override
            public boolean includeTable(Table table) {
                return VALIDATED_TABLES.contains(table.getName().replace("\"", "").toLowerCase(Locale.ROOT));
            }

            @Override
            public boolean includeSequence(Sequence sequence) {
                return false;
            }
        };

        @Override
        public SchemaFilter getCreateFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getDropFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getTruncatorFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getMigrateFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getValidateFilter() {
            return BANK_TABLES;
        }
    }

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
            // A changeset may need an ADMIN user the app seeds at startup: seed one and resume.
            seedAdminUser();
            runLiquibase();
        }
        migrated = true;
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void runLiquibase() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUser() throws Exception {
        try (Connection c = newConnection(); Statement st = c.createStatement()) {
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

    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankConnectionSecretDao secretDao;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankBalanceGapDao gapDao;
    @Autowired private BankSyncRunDao runDao;
    @Autowired private BankSyncRunAccountDao runAccountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private BankTransactionSuggestionDao suggestionDao;
    @Autowired private BankCounterpartyRuleDao ruleDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private WorkerPayoutDao payoutDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("connection + secret + BANK account + gap + sync run + run account round-trip; lookups by external id and active run")
    void connectionAccountAndRunRoundTrip() {
        Long[] ids = tx().execute(s -> {
            BankConnectionEntity connection = newConnection("CONN-" + runId);
            connection.setHolderNames(Map.of("names", List.of("Foremen Sp. z o.o."), "taxIds", List.of("1234567890")));
            connection = connectionDao.save(connection);

            BankConnectionSecretEntity secret = new BankConnectionSecretEntity();
            secret.setConnectionId(connection.getId());
            secret.setCiphertext(new byte[] {1, 2, 3, 4});
            secret.setIv(new byte[12]);
            secret.setKeyVersion((short) 1);
            secret.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            secret.setUpdatedBy("test");
            secretDao.save(secret);

            BankAccountEntity account = newBankAccount(connection, "ACC-" + runId);
            account.setHolderMatch(HolderMatch.MATCH);
            account = accountDao.save(account);

            BankBalanceGapEntity gap = new BankBalanceGapEntity();
            gap.setAccount(account);
            gap.setExpected(new BigDecimal("100.00"));
            gap.setActual(new BigDecimal("90.00"));
            gap.setDifference(new BigDecimal("-10.00"));
            gapDao.save(gap);

            BankSyncRunEntity run = new BankSyncRunEntity();
            run.setConnection(connection);
            run.setTrigger(SyncTrigger.MANUAL);
            run.setStatus(SyncRunStatus.RUNNING);
            run.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
            run = runDao.save(run);

            BankSyncRunAccountEntity runAccount = new BankSyncRunAccountEntity();
            runAccount.setRun(run);
            runAccount.setAccount(account);
            runAccount.setTransactionsNew(3);
            runAccount.setStatus(SyncRunStatus.SUCCEEDED);
            runAccountDao.save(runAccount);
            return new Long[] {connection.getId(), account.getId(), run.getId()};
        });

        tx().executeWithoutResult(s -> {
            BankConnectionEntity c = connectionDao
                    .findByProviderAndExternalConnectionId(FeedProvider.STUB, "CONN-" + runId).orElseThrow();
            assertThat(c.getId()).isEqualTo(ids[0]);
            assertThat(c.getProviderMode()).isEqualTo(SaltEdgeMode.CLIENT);
            assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);
            assertThat(c.getHolderNames()).containsKey("names");

            BankConnectionSecretEntity secret = secretDao.findById(ids[0]).orElseThrow();
            assertThat(secret.getCiphertext()).containsExactly(1, 2, 3, 4);
            assertThat(secret.getIv()).hasSize(12);
            assertThat(secret.getKeyVersion()).isEqualTo((short) 1);

            BankAccountEntity a = accountDao.findByConnectionIdAndExternalId(ids[0], "ACC-" + runId).orElseThrow();
            assertThat(a.getId()).isEqualTo(ids[1]);
            assertThat(a.getCurrency()).isEqualTo("PLN");
            assertThat(a.getHolderMatch()).isEqualTo(HolderMatch.MATCH);
            assertThat(a.isIncludedInLedger()).isTrue();
            assertThat(gapDao.findByAccountIdAndAcknowledgedAtIsNull(ids[1])).hasSize(1);

            BankSyncRunEntity active = runDao.findActiveByConnectionId(ids[0]).orElseThrow();
            assertThat(active.getId()).isEqualTo(ids[2]);
            assertThat(active.getTrigger()).isEqualTo(SyncTrigger.MANUAL);
            assertThat(runAccountDao.findByRunIdAndAccountId(ids[2], ids[1])).get()
                    .extracting(BankSyncRunAccountEntity::getTransactionsNew).isEqualTo(3);

            active.setStatus(SyncRunStatus.SUCCEEDED);
        });

        assertThat(runDao.findActiveByConnectionId(ids[0])).as("no active run after SUCCEEDED").isEmpty();
    }

    @Test
    @DisplayName("BANK_FEED line + default CASH register + SYSTEM cash line; lookups by external id, counterparty key, system source, default cash")
    void ledgerLinesRoundTrip() {
        String currency = cashCurrency();
        Long[] ids = tx().execute(s -> {
            BankConnectionEntity connection = connectionDao.save(newConnection("CONN2-" + runId));
            BankAccountEntity bank = accountDao.save(newBankAccount(connection, "ACC2-" + runId));
            BankAccountEntity cash = accountDao.save(newCashAccount(currency));

            BankTransactionEntity feed = newBankLine(bank, "TX-" + runId, new BigDecimal("-120.50"));
            feed.setCounterpartyKey("NAME:SUPPLIER " + runId);
            feed.setCounterpartyRaw(Map.of("name", "Supplier " + runId));
            feed.setProviderPayload(Map.of("id", "TX-" + runId, "extra", Map.of("k", "v")));
            feed = transactionDao.save(feed);

            BankTransactionEntity system = new BankTransactionEntity();
            system.setAccount(cash);
            system.setChannel(LedgerChannel.CASH);
            system.setOrigin(LedgerOrigin.SYSTEM);
            system.setSystemSource(SystemSource.PAYROLL_PAYOUT);
            system.setSystemSourceId(990_000L + Math.abs(runId.hashCode() % 1000));
            system.setStatus(BankTxnStatus.POSTED);
            system.setAmount(new BigDecimal("-50.00"));
            system.setCurrency(currency);
            system.setOperationDate(LocalDate.now());
            system.setBookingDate(LocalDate.now());
            system.setCreatedByUser("test");
            system = transactionDao.save(system);
            return new Long[] {bank.getId(), cash.getId(), feed.getId(), system.getId(), system.getSystemSourceId()};
        });

        tx().executeWithoutResult(s -> {
            BankTransactionEntity feed = transactionDao.findByAccountIdAndExternalId(ids[0], "TX-" + runId).orElseThrow();
            assertThat(feed.getId()).isEqualTo(ids[2]);
            assertThat(feed.getChannel()).isEqualTo(LedgerChannel.BANK);
            assertThat(feed.getLinkStatus()).isEqualTo(LinkStatus.UNLINKED);
            assertThat(feed.getAmount()).isEqualByComparingTo("-120.50");
            assertThat(feed.getCurrency()).isEqualTo("PLN");
            assertThat(feed.getProviderPayload()).containsEntry("id", "TX-" + runId).containsKey("extra");
            assertThat(transactionDao.findByCounterpartyKey("NAME:SUPPLIER " + runId))
                    .extracting(BankTransactionEntity::getId).containsExactly(ids[2]);

            assertThat(accountDao.findDefaultCashAccount(currency)).get()
                    .extracting(BankAccountEntity::getId).isEqualTo(ids[1]);
            BankTransactionEntity system = transactionDao
                    .findBySystemSourceAndSystemSourceId(SystemSource.PAYROLL_PAYOUT, ids[4]).orElseThrow();
            assertThat(system.getId()).isEqualTo(ids[3]);
            assertThat(system.getOrigin()).isEqualTo(LedgerOrigin.SYSTEM);
            assertThat(system.getAccount().getType()).isEqualTo(BankAccountType.CASH);
        });
    }

    @Test
    @DisplayName("links to payout / document + suggestion + counterparty rule; Σ link amount per payout and per document; procurement invoice fields")
    void linksSuggestionsRulesAndSums() {
        Long[] ids = tx().execute(s -> {
            UserEntity worker = newUser();
            ProjectEntity project = new ProjectEntity();
            project.setName("Bank project " + runId);
            project.setStatus(ProjectStatus.ACTIVE);
            project = projectDao.save(project);

            WorkerPayoutEntity payout = new WorkerPayoutEntity();
            payout.setWorker(worker);
            payout.setPayoutDate(LocalDate.now());
            payout.setPeriodFrom(LocalDate.now().minusDays(7));
            payout.setPeriodTo(LocalDate.now().minusDays(1));
            payout.setAmount(new BigDecimal("300.00"));
            payout.setSuggestedAmount(new BigDecimal("300.00"));
            payout.setPaymentChannel(PaymentChannel.BANK_TRANSFER);
            payout.setStatus(PayoutStatus.PLANNED);
            payout = payoutDao.save(payout);

            ProcurementDocumentEntity document = new ProcurementDocumentEntity();
            document.setProject(project);
            document.setUploadedBy(worker);
            document.setRawObjectKey("raw/" + runId);
            document.setSourceMimeType("application/pdf");
            document.setStatus(ProcessingStatus.PARSED);
            document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
            document.setDocumentTotal(new BigDecimal("200.00"));
            document.setSupplierNip("1234567890");
            document.setInvoiceNumber("FV/" + runId);
            document.setInvoiceDate(LocalDate.of(2026, 1, 15));
            document = documentDao.save(document);

            BankConnectionEntity connection = connectionDao.save(newConnection("CONN3-" + runId));
            BankAccountEntity bank = accountDao.save(newBankAccount(connection, "ACC3-" + runId));
            BankTransactionEntity line1 = transactionDao.save(newBankLine(bank, "TXA-" + runId, new BigDecimal("-250.00")));
            BankTransactionEntity line2 = transactionDao.save(newBankLine(bank, "TXB-" + runId, new BigDecimal("-200.00")));

            linkDao.save(newLink(line1, LinkKind.WORKER_PAYOUT, "100.00", payout, null));
            linkDao.save(newLink(line1, LinkKind.PROCUREMENT, "150.00", null, document));
            linkDao.save(newLink(line2, LinkKind.WORKER_PAYOUT, "120.00", payout, null));
            BankTransactionLinkEntity confirmed = newLink(line2, LinkKind.PROCUREMENT, "30.00", null, document);
            confirmed.setSource(DetectionSource.RULE);
            confirmed.setConfidence(new BigDecimal("0.950"));
            confirmed.setConfirmedAt(OffsetDateTime.now(ZoneOffset.UTC));
            confirmed.setConfirmedBy("test");
            linkDao.save(confirmed);

            BankTransactionSuggestionEntity suggestion = new BankTransactionSuggestionEntity();
            suggestion.setTransaction(line2);
            suggestion.setKind(LinkKind.PROCUREMENT);
            suggestion.setAmount(new BigDecimal("200.00"));
            suggestion.setProcurementDocument(document);
            suggestion.setRank((short) 1);
            suggestion.setRationale("Invoice number in title");
            suggestion.setSource(DetectionSource.AI);
            suggestion.setConfidence(new BigDecimal("0.700"));
            suggestionDao.save(suggestion);

            BankCounterpartyRuleEntity rule = new BankCounterpartyRuleEntity();
            rule.setCounterpartyKey("IBAN:PL" + runId);
            rule.setDisplayName("Worker " + runId);
            rule.setTargetKind(LinkKind.WORKER_PAYOUT);
            rule.setWorker(worker);
            rule.setOrigin(RuleOrigin.MANUAL);
            ruleDao.save(rule);
            return new Long[] {payout.getId(), document.getId(), line2.getId()};
        });

        tx().executeWithoutResult(s -> {
            assertThat(linkDao.sumAmountByWorkerPayoutId(ids[0])).isEqualByComparingTo("220.00");
            assertThat(linkDao.sumAmountByProcurementDocumentId(ids[1])).isEqualByComparingTo("180.00");
            assertThat(linkDao.sumAmountByWorkerPayoutId(-1L)).isEqualByComparingTo("0");
            assertThat(linkDao.sumAmountByWorkerPayoutIds(List.of(ids[0], -1L)))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.targetId()).isEqualTo(ids[0]);
                        assertThat(r.total()).isEqualByComparingTo("220.00");
                    });
            assertThat(linkDao.sumAmountByProcurementDocumentIds(List.of(ids[1])))
                    .extracting(BankLinkedAmount::total).singleElement()
                    .satisfies(t -> assertThat(t).isEqualByComparingTo("180.00"));
            assertThat(linkDao.findByWorkerPayoutId(ids[0])).hasSize(2);
            assertThat(linkDao.findByProcurementDocumentId(ids[1]))
                    .anySatisfy(l -> assertThat(l.getConfidence()).isEqualByComparingTo("0.950"));

            assertThat(suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(ids[2], SuggestionStatus.OPEN))
                    .singleElement()
                    .satisfies(sg -> assertThat(sg.getRationale()).isEqualTo("Invoice number in title"));

            BankCounterpartyRuleEntity rule = ruleDao.findByCounterpartyKeyAndEnabledTrue("IBAN:PL" + runId).orElseThrow();
            assertThat(rule.getOrigin()).isEqualTo(RuleOrigin.MANUAL);
            assertThat(rule.getHits()).isZero();

            ProcurementDocumentEntity document = documentDao.findById(ids[1]).orElseThrow();
            assertThat(document.getSupplierNip()).isEqualTo("1234567890");
            assertThat(document.getInvoiceNumber()).isEqualTo("FV/" + runId);
            assertThat(document.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        });
    }

    // --- builders -------------------------------------------------------------------------------

    private BankConnectionEntity newConnection(String externalId) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setProvider(FeedProvider.STUB);
        c.setProviderMode(SaltEdgeMode.CLIENT);
        c.setBankName("Stub Bank");
        c.setExternalConnectionId(externalId);
        c.setStatus(BankConnectionStatus.ACTIVE);
        return c;
    }

    private BankAccountEntity newBankAccount(BankConnectionEntity connection, String externalId) {
        BankAccountEntity a = new BankAccountEntity();
        a.setType(BankAccountType.BANK);
        a.setConnection(connection);
        a.setExternalId(externalId);
        a.setName("Main");
        a.setIban("PL61109010140000071219812874");
        a.setCurrency("PLN");
        a.setBalance(new BigDecimal("1000.00"));
        return a;
    }

    private BankAccountEntity newCashAccount(String currency) {
        BankAccountEntity a = new BankAccountEntity();
        a.setType(BankAccountType.CASH);
        a.setName("Cash " + runId);
        a.setCurrency(currency);
        a.setOpeningBalance(new BigDecimal("500.00"));
        a.setDefaultForCash(true);
        return a;
    }

    private BankTransactionEntity newBankLine(BankAccountEntity account, String externalId, BigDecimal amount) {
        BankTransactionEntity t = new BankTransactionEntity();
        t.setAccount(account);
        t.setChannel(LedgerChannel.BANK);
        t.setOrigin(LedgerOrigin.BANK_FEED);
        t.setExternalId(externalId);
        t.setStatus(BankTxnStatus.POSTED);
        t.setAmount(amount);
        t.setCurrency("PLN");
        t.setOperationDate(LocalDate.now());
        t.setBookingDate(LocalDate.now());
        t.setTitle("Payment " + externalId);
        return t;
    }

    private BankTransactionLinkEntity newLink(BankTransactionEntity line, LinkKind kind, String amount,
                                              WorkerPayoutEntity payout, ProcurementDocumentEntity document) {
        BankTransactionLinkEntity l = new BankTransactionLinkEntity();
        l.setTransaction(line);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        l.setWorkerPayout(payout);
        l.setProcurementDocument(document);
        l.setSource(DetectionSource.MANUAL);
        return l;
    }

    private UserEntity newUser() {
        UserEntity user = new UserEntity();
        user.setName("Bank worker " + runId);
        user.setEmail("bank-worker+" + runId.toLowerCase(Locale.ROOT) + "@example.com");
        user.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    /** A per-run 3-letter pseudo currency so the one-default-cash-per-currency index never collides. */
    private String cashCurrency() {
        char a = (char) ('A' + Math.floorMod(runId.hashCode(), 26));
        char b = (char) ('A' + Math.floorMod(runId.hashCode() / 26, 26));
        return "Q" + a + b;
    }
}
