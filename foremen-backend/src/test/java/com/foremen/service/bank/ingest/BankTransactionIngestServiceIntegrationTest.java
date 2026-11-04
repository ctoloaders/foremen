package com.foremen.service.bank.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 6.3 — {@link BankTransactionIngestService} against the real {@code bank_transactions}
 * schema (Liquibase changelog on Testcontainers PostgreSQL), so the DB CHECKs (BANK ⇒ BANK_FEED with an
 * external id, POSTED ⇒ booking date, amount ≠ 0) and the partial UNIQUE {@code (account_id,
 * external_id)} are exercised.
 *
 * <p>Properties 1 and 2 are checked over seeded random feeds (each trial logs nothing but uses a
 * deterministic seed, so a failure message names the seed to replay).
 *
 * <p>Repeatability: a fresh container per run; every connection / account external id carries a per-run
 * and per-trial id.
 *
 * <p>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 6.3: transaction ingest")
class BankTransactionIngestServiceIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IBAN = "PL61109010140000071219812874";
    private static final String NRB_SPACED = "61 1090 1014 0000 0712 1981 2874";
    private static final int TRIALS = 15;

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

    @Autowired private BankTransactionIngestService ingestService;
    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankSyncRunDao runDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private int fixtureSeq;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // --- Property 1 -----------------------------------------------------------------------------

    /**
     * Property 1 (ingest idempotence): ingesting the same fetch twice yields the same ledger state —
     * no new rows, no field changes, links / ignore / review state and {@code first_sync_run} untouched.
     *
     * <p>Validates: Requirements 6.2, 6.4, 6.7
     */
    @Test
    @DisplayName("Property 1: re-ingesting the same fetch leaves the ledger unchanged and keeps links")
    void property1IngestIsIdempotent() {
        for (int trial = 0; trial < TRIALS; trial++) {
            long seed = 0x6_3_01L * 1000 + trial;
            Random random = new Random(seed);
            List<TransactionPage> pages = randomFetch(random);
            Fixture f = newFixture();

            IngestResult first = tx().execute(s -> ingestService.ingestAll(account(f), pages, f.run1));
            assertThat(first.newPostedIds()).as("seed %d", seed).hasSize(first.transactionsNew());

            // User work between syncs: link one POSTED line, ignore another.
            List<Long> posted = first.newPostedIds();
            if (!posted.isEmpty()) {
                tx().executeWithoutResult(s -> {
                    BankTransactionEntity linked = transactionDao.findById(posted.get(0)).orElseThrow();
                    BankTransactionLinkEntity link = new BankTransactionLinkEntity();
                    link.setTransaction(linked);
                    link.setKind(LinkKind.OTHER_INCOME);
                    link.setAmount(linked.getAmount().abs());
                    link.setSource(DetectionSource.MANUAL);
                    link.setConfirmedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    link.setConfirmedBy("test");
                    linkDao.save(link);
                    linked.setLinkStatus(LinkStatus.LINKED);
                    linked.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    linked.setReviewedBy("test");
                    if (posted.size() > 1) {
                        BankTransactionEntity ignored = transactionDao.findById(posted.get(1)).orElseThrow();
                        ignored.setLinkStatus(LinkStatus.IGNORED);
                        ignored.setIgnoredReason("private");
                        ignored.setIgnoredBy("test");
                        ignored.setIgnoredAt(OffsetDateTime.now(ZoneOffset.UTC));
                    }
                });
            }

            Map<String, LineState> before = ledgerState(f.accountId);
            Map<Long, Integer> linksBefore = linkCounts(before);

            IngestResult second = tx().execute(s -> ingestService.ingestAll(account(f), pages, f.run2));

            assertThat(second.transactionsNew()).as("seed %d: new rows", seed).isZero();
            assertThat(second.transactionsUpdated()).as("seed %d: updated rows", seed).isZero();
            assertThat(second.pendingInserted()).as("seed %d", seed).isZero();
            assertThat(second.pendingDeleted()).as("seed %d", seed).isZero();
            assertThat(second.pendingUpdated()).as("seed %d", seed).isZero();
            assertThat(second.newPostedIds()).as("seed %d", seed).isEmpty();
            assertThat(second.rowsSkipped()).as("seed %d: same skips", seed).isEqualTo(first.rowsSkipped());
            assertThat(ledgerState(f.accountId)).as("seed %d: ledger state", seed).isEqualTo(before);
            assertThat(linkCounts(before)).as("seed %d: links", seed).isEqualTo(linksBefore);
        }
    }

    // --- Property 2 -----------------------------------------------------------------------------

    /**
     * Property 2 (pending replacement): after every fetch the account's PENDING lines equal the fetched
     * pending list (minus skipped rows and ids already POSTED); a pending id seen again keeps its row;
     * POSTED lines are never deleted.
     *
     * <p>Validates: Requirements 6.3, 6.7
     */
    @Test
    @DisplayName("Property 2: PENDING rows equal the latest pending list; promotions and POSTED rows survive")
    void property2PendingIsReplacedWholesale() {
        for (int trial = 0; trial < TRIALS; trial++) {
            long seed = 0x6_3_02L * 1000 + trial;
            Random random = new Random(seed);
            Fixture f = newFixture();
            Set<String> postedSoFar = new LinkedHashSet<>();
            Map<String, Long> pendingRowIds = new HashMap<>();

            for (int fetch = 0; fetch < 4; fetch++) {
                // Some pending ids get booked (promotion) in this fetch.
                List<NormalizedTransaction> postedRows = new ArrayList<>();
                for (String id : new ArrayList<>(pendingRowIds.keySet())) {
                    if (random.nextInt(4) == 0) {
                        postedRows.add(row(id, TxnStatus.POSTED, randomAmount(random), random, false));
                    }
                }
                List<NormalizedTransaction> pendingRows = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    if (random.nextBoolean()) {
                        pendingRows.add(row("Q" + i, TxnStatus.PENDING, randomAmount(random), random,
                                random.nextInt(10) == 0));
                    }
                }
                if (random.nextInt(5) == 0) {
                    pendingRows.add(row("Q-zero", TxnStatus.PENDING, new BigDecimal("0.00"), random, false));
                }
                TransactionPage page = new TransactionPage(postedRows, pendingRows, null, true, 0);

                Map<String, Long> pendingBefore = Map.copyOf(pendingRowIds);
                IngestResult result = tx().execute(s -> ingestService.ingestAll(account(f), List.of(page), f.run1));

                postedRows.forEach(r -> postedSoFar.add(r.externalId()));
                Set<String> expectedPending = pendingRows.stream()
                        .filter(r -> !r.providerDuplicate() && r.amount().signum() != 0)
                        .map(NormalizedTransaction::externalId)
                        .filter(id -> !postedSoFar.contains(id))
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                Map<String, LineState> state = ledgerState(f.accountId);
                Map<String, Long> pendingNow = state.values().stream()
                        .filter(l -> l.status() == BankTxnStatus.PENDING)
                        .collect(Collectors.toMap(LineState::externalId, LineState::id));
                assertThat(pendingNow.keySet()).as("seed %d fetch %d", seed, fetch).isEqualTo(expectedPending);
                pendingNow.forEach((id, rowId) -> {
                    if (pendingBefore.containsKey(id)) {
                        assertThat(rowId).as("seed %d: pending %s upserted in place", seed, id)
                                .isEqualTo(pendingBefore.get(id));
                    }
                });

                Set<String> postedNow = state.values().stream()
                        .filter(l -> l.status() == BankTxnStatus.POSTED)
                        .map(LineState::externalId).collect(Collectors.toSet());
                assertThat(postedNow).as("seed %d: POSTED never deleted", seed).isEqualTo(postedSoFar);
                for (NormalizedTransaction p : postedRows) {
                    assertThat(state.get(p.externalId()).id()).as("seed %d: promoted in place", seed)
                            .isEqualTo(pendingBefore.get(p.externalId()));
                }
                assertThat(result.promoted()).isEqualTo(postedRows.size());
                assertThat(result.newPostedIds()).containsExactlyInAnyOrderElementsOf(
                        postedRows.stream().map(r -> pendingBefore.get(r.externalId())).toList());

                pendingRowIds.clear();
                pendingRowIds.putAll(pendingNow);
            }
        }
    }

    // --- examples -------------------------------------------------------------------------------

    @Test
    @DisplayName("insert maps fields: BANK/BANK_FEED, UNLINKED, run, trimmed title, IBAN key, scale 2, account currency")
    void insertMapsFields() {
        Fixture f = newFixture();
        ObjectNode cpRaw = JSON.createObjectNode().put("payee", "ACME");
        NormalizedTransaction posted = new NormalizedTransaction("T1", TxnStatus.POSTED, new BigDecimal("-12.5"),
                "pln", LocalDate.of(2026, 3, 1), null, "  Invoice FV/1  ", "  ACME   Sp. z o.o. ", NRB_SPACED,
                "shopping", cpRaw, JSON.createObjectNode().put("id", "T1"), false);
        NormalizedTransaction invalidIban = new NormalizedTransaction("T2", TxnStatus.POSTED, new BigDecimal("40"),
                "PLN", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3), "x", "Jan Kowalski", "PL00123",
                null, null, null, false);
        NormalizedTransaction dup = new NormalizedTransaction("T3", TxnStatus.POSTED, BigDecimal.TEN, "PLN",
                LocalDate.of(2026, 3, 2), null, "dup", null, null, null, null, null, true);
        NormalizedTransaction zero = new NormalizedTransaction("T4", TxnStatus.POSTED, new BigDecimal("0.004"),
                "PLN", LocalDate.of(2026, 3, 2), null, "zero", null, null, null, null, null, false);
        NormalizedTransaction noId = new NormalizedTransaction(null, TxnStatus.POSTED, BigDecimal.ONE, "PLN",
                LocalDate.of(2026, 3, 2), null, "no id", null, null, null, null, null, false);
        TransactionPage page = new TransactionPage(List.of(posted, invalidIban, dup, zero, noId), List.of(),
                null, true, 2);

        IngestResult result = tx().execute(s -> ingestService.ingest(account(f), page, f.run1));

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.skippedDuplicate()).isEqualTo(1);
        assertThat(result.skippedZeroAmount()).isEqualTo(1);
        assertThat(result.skippedMalformed()).isEqualTo(3);
        assertThat(result.rowsSkipped()).isEqualTo(5);
        assertThat(result.newPostedIds()).hasSize(2);

        tx().executeWithoutResult(s -> {
            BankTransactionEntity t1 = transactionDao.findByAccountIdAndExternalId(f.accountId, "T1").orElseThrow();
            assertThat(t1.getChannel()).isEqualTo(LedgerChannel.BANK);
            assertThat(t1.getOrigin()).isEqualTo(LedgerOrigin.BANK_FEED);
            assertThat(t1.getStatus()).isEqualTo(BankTxnStatus.POSTED);
            assertThat(t1.getLinkStatus()).isEqualTo(LinkStatus.UNLINKED);
            assertThat(t1.getFirstSyncRun().getId()).isEqualTo(f.run1);
            assertThat(t1.getAmount()).isEqualByComparingTo("-12.50");
            assertThat(t1.getAmount().scale()).isEqualTo(2);
            assertThat(t1.getCurrency()).isEqualTo("PLN");
            assertThat(t1.getBookingDate()).as("POSTED falls back to operation date").isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(t1.getTitle()).isEqualTo("Invoice FV/1");
            assertThat(t1.getCounterpartyName()).isEqualTo("ACME Sp. z o.o.");
            assertThat(t1.getCounterpartyIban()).isEqualTo(IBAN);
            assertThat(t1.getCounterpartyKey()).isEqualTo("IBAN:" + IBAN);
            assertThat(t1.getProviderCategory()).isEqualTo("shopping");
            assertThat(t1.getCounterpartyRaw()).containsEntry("payee", "ACME");
            assertThat(t1.getProviderPayload()).containsEntry("id", "T1");

            BankTransactionEntity t2 = transactionDao.findByAccountIdAndExternalId(f.accountId, "T2").orElseThrow();
            assertThat(t2.getCounterpartyIban()).isNull();
            assertThat(t2.getCounterpartyRaw()).containsEntry(BankTransactionIngestService.RAW_IBAN_KEY, "PL00123");
            assertThat(t2.getCounterpartyKey()).startsWith(CounterpartyExtractor.NAME_PREFIX);
            assertThat(transactionDao.findByAccountIdAndExternalId(f.accountId, "T3")).isEmpty();
            assertThat(transactionDao.findByAccountIdAndExternalId(f.accountId, "T4")).isEmpty();
        });
    }

    @Test
    @DisplayName("re-delivery updates only mutable fields and lastSeenAt; amount, run, links and review stay")
    void redeliveryUpdatesMutableFieldsOnly() {
        Fixture f = newFixture();
        NormalizedTransaction v1 = new NormalizedTransaction("U1", TxnStatus.POSTED, new BigDecimal("-100.00"),
                "PLN", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1), "old", "Old Name", null, null,
                null, null, false);
        tx().execute(s -> ingestService.ingest(account(f), new TransactionPage(List.of(v1), List.of(), null, true, 0), f.run1));
        OffsetDateTime[] seenBefore = new OffsetDateTime[1];
        tx().executeWithoutResult(s -> {
            BankTransactionEntity line = transactionDao.findByAccountIdAndExternalId(f.accountId, "U1").orElseThrow();
            BankTransactionLinkEntity link = new BankTransactionLinkEntity();
            link.setTransaction(line);
            link.setKind(LinkKind.OTHER_INCOME);
            link.setAmount(new BigDecimal("100.00"));
            link.setSource(DetectionSource.MANUAL);
            linkDao.save(link);
            line.setLinkStatus(LinkStatus.LINKED);
            line.setReviewedBy("test");
            seenBefore[0] = line.getLastSeenAt();
        });

        NormalizedTransaction v2 = new NormalizedTransaction("U1", TxnStatus.POSTED, new BigDecimal("-999.00"),
                "PLN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 2), " new ", "New Name", IBAN, "fees",
                null, JSON.createObjectNode().put("v", 2), false);
        IngestResult result = tx().execute(s -> ingestService.ingest(account(f),
                new TransactionPage(List.of(v2), List.of(), null, true, 0), f.run2));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.transactionsNew()).isZero();
        assertThat(result.newPostedIds()).isEmpty();
        tx().executeWithoutResult(s -> {
            BankTransactionEntity line = transactionDao.findByAccountIdAndExternalId(f.accountId, "U1").orElseThrow();
            assertThat(line.getTitle()).isEqualTo("new");
            assertThat(line.getCounterpartyName()).isEqualTo("New Name");
            assertThat(line.getCounterpartyKey()).isEqualTo("IBAN:" + IBAN);
            assertThat(line.getBookingDate()).isEqualTo(LocalDate.of(2026, 4, 2));
            assertThat(line.getProviderCategory()).isEqualTo("fees");
            assertThat(line.getProviderPayload()).containsEntry("v", 2);
            assertThat(line.getAmount()).as("amount immutable").isEqualByComparingTo("-100.00");
            assertThat(line.getOperationDate()).as("operation date immutable").isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(line.getFirstSyncRun().getId()).isEqualTo(f.run1);
            assertThat(line.getLinkStatus()).isEqualTo(LinkStatus.LINKED);
            assertThat(line.getReviewedBy()).isEqualTo("test");
            assertThat(line.getLastSeenAt()).isAfterOrEqualTo(seenBefore[0]);
            assertThat(linkDao.findByTransactionIdOrderByIdAsc(line.getId())).hasSize(1);
        });
    }

    @Test
    @DisplayName("CASH accounts are rejected")
    void cashAccountRejected() {
        BankAccountEntity cash = new BankAccountEntity();
        cash.setId(1L);
        cash.setType(BankAccountType.CASH);
        assertThatThrownBy(() -> ingestService.ingest(cash, new TransactionPage(List.of(), List.of(), null, true, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- generators -----------------------------------------------------------------------------

    /** 1–3 pages with disjoint POSTED ids (in-page repeats allowed), the pending snapshot on page 1. */
    private static List<TransactionPage> randomFetch(Random random) {
        int pageCount = 1 + random.nextInt(3);
        List<TransactionPage> pages = new ArrayList<>();
        int nextId = 0;
        for (int p = 0; p < pageCount; p++) {
            List<NormalizedTransaction> posted = new ArrayList<>();
            int size = random.nextInt(7);
            for (int i = 0; i < size; i++) {
                String id = "P" + nextId++;
                BigDecimal amount = random.nextInt(12) == 0 ? new BigDecimal("0.001") : randomAmount(random);
                posted.add(row(id, TxnStatus.POSTED, amount, random, random.nextInt(8) == 0));
                if (random.nextInt(6) == 0) {
                    posted.add(row(id, TxnStatus.POSTED, randomAmount(random), random, false)); // in-page repeat
                }
            }
            List<NormalizedTransaction> pending = new ArrayList<>();
            if (p == 0) {
                int pendingSize = random.nextInt(5);
                for (int i = 0; i < pendingSize; i++) {
                    pending.add(row("Q" + i, TxnStatus.PENDING, randomAmount(random), random, random.nextInt(8) == 0));
                }
                if (nextId > 0 && random.nextBoolean()) {
                    // A pending row whose id is POSTED in the same fetch never demotes it.
                    pending.add(row("P0", TxnStatus.PENDING, randomAmount(random), random, false));
                }
            }
            boolean last = p == pageCount - 1;
            pages.add(new TransactionPage(posted, pending, last ? null : "c" + p, last, random.nextInt(2)));
        }
        return pages;
    }

    private static NormalizedTransaction row(String id, TxnStatus status, BigDecimal amount, Random random,
                                             boolean duplicate) {
        LocalDate op = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(60));
        String iban = switch (random.nextInt(4)) {
            case 0 -> IBAN;
            case 1 -> NRB_SPACED;
            case 2 -> "PL00123";
            default -> null;
        };
        String name = random.nextBoolean() ? "Supplier " + random.nextInt(5) + " Sp. z o.o." : null;
        ObjectNode payload = JSON.createObjectNode().put("id", id).put("n", random.nextInt(1000));
        ObjectNode cpRaw = random.nextBoolean() ? JSON.createObjectNode().put("payee", String.valueOf(name)) : null;
        return new NormalizedTransaction(id, status, amount, "PLN", op,
                status == TxnStatus.POSTED && random.nextBoolean() ? op.plusDays(1) : null,
                "  Title " + random.nextInt(100) + " ", name, iban,
                random.nextBoolean() ? "cat" + random.nextInt(3) : null, cpRaw, payload, duplicate);
    }

    private static BigDecimal randomAmount(Random random) {
        long cents = 1 + random.nextInt(500_000);
        return BigDecimal.valueOf(random.nextBoolean() ? cents : -cents, 2);
    }

    // --- state / fixtures -----------------------------------------------------------------------

    /** Comparable state of a ledger line (everything but the volatile {@code lastSeenAt}). */
    record LineState(Long id, String externalId, BankTxnStatus status, BigDecimal amount, String currency,
                     LocalDate operationDate, LocalDate bookingDate, String title, String counterpartyName,
                     String counterpartyIban, String counterpartyKey, String providerCategory,
                     Map<String, Object> counterpartyRaw, Map<String, Object> providerPayload,
                     LinkStatus linkStatus, String ignoredReason, String reviewedBy, Long firstSyncRunId,
                     LocalDateTime createdDate) {}

    private Map<String, LineState> ledgerState(Long accountId) {
        return tx().execute(s -> Stream.concat(
                        transactionDao.findByAccountIdAndStatus(accountId, BankTxnStatus.POSTED).stream(),
                        transactionDao.findByAccountIdAndStatus(accountId, BankTxnStatus.PENDING).stream())
                .sorted(Comparator.comparing(BankTransactionEntity::getId))
                .collect(Collectors.toMap(BankTransactionEntity::getExternalId, t -> new LineState(
                        t.getId(), t.getExternalId(), t.getStatus(), t.getAmount(), t.getCurrency(),
                        t.getOperationDate(), t.getBookingDate(), t.getTitle(), t.getCounterpartyName(),
                        t.getCounterpartyIban(), t.getCounterpartyKey(), t.getProviderCategory(),
                        t.getCounterpartyRaw(), t.getProviderPayload(), t.getLinkStatus(), t.getIgnoredReason(),
                        t.getReviewedBy(), t.getFirstSyncRun() == null ? null : t.getFirstSyncRun().getId(),
                        t.getCreatedDate()),
                        (a, b) -> a, LinkedHashMap::new)));
    }

    private Map<Long, Integer> linkCounts(Map<String, LineState> state) {
        return tx().execute(s -> state.values().stream().collect(Collectors.toMap(LineState::id,
                l -> linkDao.findByTransactionIdOrderByIdAsc(l.id()).size())));
    }

    private record Fixture(Long accountId, Long run1, Long run2) {}

    private BankAccountEntity account(Fixture f) {
        return accountDao.findById(f.accountId).orElseThrow();
    }

    private Fixture newFixture() {
        String suffix = runId + "-" + (fixtureSeq++);
        return tx().execute(s -> {
            BankConnectionEntity c = new BankConnectionEntity();
            c.setProvider(FeedProvider.STUB);
            c.setProviderMode(SaltEdgeMode.CLIENT);
            c.setBankName("Stub Bank");
            c.setExternalConnectionId("ING-CONN-" + suffix);
            c.setStatus(BankConnectionStatus.ACTIVE);
            c = connectionDao.save(c);

            BankAccountEntity a = new BankAccountEntity();
            a.setType(BankAccountType.BANK);
            a.setConnection(c);
            a.setExternalId("ING-ACC-" + suffix);
            a.setName("Main");
            a.setCurrency("PLN");
            a = accountDao.save(a);
            return new Fixture(a.getId(), newRun(c).getId(), newRun(c).getId());
        });
    }

    private BankSyncRunEntity newRun(BankConnectionEntity connection) {
        BankSyncRunEntity run = new BankSyncRunEntity();
        run.setConnection(connection);
        run.setTrigger(SyncTrigger.MANUAL);
        run.setStatus(SyncRunStatus.SUCCEEDED);
        run.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return runDao.save(run);
    }
}
