package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankCounterpartyRuleDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTrancheDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.TxnStatus;
import com.foremen.service.bank.connector.stub.StubDataStore;
import com.foremen.service.bank.connector.stub.StubSeed;
import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.ScheduleStatus;
import com.foremen.service.payment.TrancheStatus;
import com.foremen.service.payment.TransactionStatus;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-07-04 task 8.1 — the STUB connector end to end over HTTP, real beans and the real Liquibase schema
 * (Testcontainers PostgreSQL): connect → complete → asynchronous sync on {@code bankSyncExecutor} →
 * holder match → ingest → detection → links and their effects in FOR-06-07 (client payments), FOR-07-06
 * (payroll) and FOR-06-03 (procurement), counterparty-rule learning, ignore, re-sync idempotence,
 * pending replacement and the balance-gap check; plus the supported-banks endpoint.
 *
 * <p>The stub data of a connection is seeded once over {@code POST /api/bank/stub/connections/{id}/data}
 * (the dev/test endpoint) and otherwise through {@link StubDataStore#seed} (same store, avoids building
 * large JSON bodies). AI is disabled. Every sync is awaited by polling {@code GET /api/bank/sync-runs/{id}}.
 *
 * <p>Repeatability: a fresh container per run; every connection, IBAN, worker, project, document and
 * amount carries a per-run id / random value, so the tests of the class never match each other's data.
 *
 * <p>Validates: Requirements 25.3, 3.7, 28.2 (R1.4, R3.2, R3.8, R5.1, R6.1–R6.3, R7.1, R13.5, R14.2–R14.6,
 * R16.1–R16.6, R17.1–R17.5, R19.9)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=true",
        "foremen.public-base-url=http://localhost:3000",
        "foremen.bank-feed.sync.cron=-",
        "foremen.bank-feed.consent.sweep-cron=-"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04-bank-ledger, task 8.1: STUB end to end")
class BankLedgerStubEndToEndIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (BankLedgerStubEndToEndIntegrationTest.class) {
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
    @Autowired private StubDataStore stubStore;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankCounterpartyRuleDao ruleDao;
    @Autowired private CompanyProfileDao companyProfileDao;
    @Autowired private PaymentScheduleDao scheduleDao;
    @Autowired private PaymentTrancheDao trancheDao;
    @Autowired private PaymentTransactionDao paymentTransactionDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UnplannedExpenseCategoryDao categoryDao;
    @Autowired private WorkerPayoutDao payoutDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final Random random = new Random();
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int seq;
    private String auth;
    private String companyName;

    @BeforeEach
    void setUp() {
        UserEntity admin = tx().execute(s -> newUser("Bank E2E admin", "ADMIN"));
        auth = "Bearer " + jwt.generateAccessToken(admin.getId(), "ADMIN", admin.getEmail());
        companyName = tx().execute(s -> {
            List<CompanyProfileEntity> profiles = new ArrayList<>();
            companyProfileDao.findAll().forEach(profiles::add);
            if (!profiles.isEmpty()) {
                return profiles.get(0).getFullName();
            }
            CompanyProfileEntity p = new CompanyProfileEntity();
            p.setFullName("Foremen Budownictwo Sp. z o.o.");
            p.setRegisteredAddress("ul. Testowa 1, 00-001 Warszawa");
            p.setNip("5260250995");
            return companyProfileDao.save(p).getFullName();
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // =============================================================================================
    // Supported banks (R19.9)
    // =============================================================================================

    @Test
    @DisplayName("supported banks with STUB: two banks, filters, fetchedAt; an empty result carries emptyReason")
    void supportedBanks() throws Exception {
        String all = body(perform(get("/api/bank/providers/STUB/banks").param("country", "PL")), 200);
        assertThat(JsonPath.<String>read(all, "$.provider")).isEqualTo("STUB");
        assertThat(JsonPath.<List<String>>read(all, "$.banks[*].code"))
                .containsExactly("stub_business_pl", "stub_personal_pl");
        assertThat(JsonPath.<Object>read(all, "$.fetchedAt")).isNotNull();
        assertThat(JsonPath.<Object>read(all, "$.emptyReason")).isNull();

        String business = body(perform(get("/api/bank/providers/STUB/banks").param("businessOnly", "true")), 200);
        assertThat(JsonPath.<List<String>>read(business, "$.banks[*].code")).containsExactly("stub_business_pl");

        // An empty *filtered* list is not an empty catalogue: emptyReason stays null and totalCount tells the
        // UI that the filters (not the provider status) hid the banks. The stub banks are test banks.
        String none = body(perform(get("/api/bank/providers/STUB/banks").param("q", "no-such-bank-" + runId)), 200);
        assertThat(JsonPath.<List<Object>>read(none, "$.banks")).isEmpty();
        assertThat(JsonPath.<Integer>read(none, "$.totalCount")).isEqualTo(2);
        assertThat(JsonPath.<Object>read(none, "$.emptyReason")).isNull();
        String realOnly = body(perform(get("/api/bank/providers/STUB/banks").param("includeTest", "false")), 200);
        assertThat(JsonPath.<List<Object>>read(realOnly, "$.banks")).isEmpty();
        assertThat(JsonPath.<Object>read(realOnly, "$.emptyReason"))
                .as("the STUB exposes test banks by design and is never reported as limited").isNull();
        String refreshed = body(perform(get("/api/bank/providers/STUB/banks").param("refresh", "true")), 200);
        assertThat(JsonPath.<String>read(refreshed, "$.fetchedAt")).isNotNull();

        String providers = body(perform(get("/api/bank/providers")), 200);
        assertThat(JsonPath.<List<String>>read(providers, "$[*].provider")).containsExactly("STUB");
    }

    // =============================================================================================
    // Connect → complete → sync → holder match → override (R1.4, R3.2, R3.7, R3.8)
    // =============================================================================================

    @Test
    @DisplayName("R3.7: connect + complete + sync — holder MATCH / MISMATCH (excluded, not ingested) / UNKNOWN; override needs a reason")
    void connectSyncHolderMatchAndOverride() throws Exception {
        long connectionId = createConnection();
        String matchIban = plIban();
        String mismatchIban = plIban();
        // Seeded over the dev/test HTTP endpoint (R4.4).
        String seed = """
                {"mode":"REPLACE","accounts":[
                  {"externalId":"A-MATCH","name":"Firmowe %1$s","nature":"account","iban":"%2$s","currency":"PLN",
                   "balance":100.00,"holderName":"%3$s"},
                  {"externalId":"A-MISMATCH","name":"Prywatne %1$s","nature":"account","iban":"%4$s","currency":"PLN",
                   "balance":50.00,"holderName":"Jan Kowalski"},
                  {"externalId":"A-UNKNOWN","name":"Bez danych %1$s","nature":"account","currency":"PLN","balance":10.00}],
                 "transactions":[
                  {"accountExternalId":"A-MATCH","externalId":"T-1","status":"POSTED","amount":-12.34,"currency":"PLN",
                   "operationDate":"%5$s","bookingDate":"%5$s","title":"Kawa %1$s","counterpartyName":"Kawiarnia %1$s"},
                  {"accountExternalId":"A-MISMATCH","externalId":"T-2","status":"POSTED","amount":-99.00,"currency":"PLN",
                   "operationDate":"%5$s","bookingDate":"%5$s","title":"Prywatny %1$s","counterpartyName":"Sklep %1$s"}]}
                """.formatted(runId, matchIban, companyName, mismatchIban, today);
        assertThat(status(post("/api/bank/stub/connections/" + connectionId + "/data")
                .contentType(MediaType.APPLICATION_JSON).content(seed))).isEqualTo(204);

        String completion = body(perform(post("/api/bank/connections/" + connectionId + "/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{\"stub\":\"1\"}}")), 200);
        assertThat(JsonPath.<Boolean>read(completion, "$.completed")).isTrue();
        long runIdConnect = JsonPath.<Number>read(completion, "$.syncRunId").longValue();
        String run = awaitRun(runIdConnect);
        assertThat(JsonPath.<String>read(run, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<String>read(run, "$.trigger")).isEqualTo("CONNECT");
        assertThat(JsonPath.<Integer>read(run, "$.accountsSeen")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(run, "$.transactionsNew")).as("only the included accounts").isEqualTo(1);

        String detail = body(perform(get("/api/bank/connections/" + connectionId)), 200);
        assertThat(JsonPath.<String>read(detail, "$.status")).isEqualTo("ACTIVE");
        Map<String, Object> match = account(detail, "Firmowe ");
        Map<String, Object> mismatch = account(detail, "Prywatne ");
        Map<String, Object> unknown = account(detail, "Bez danych ");
        assertThat(match).containsEntry("holderMatch", "MATCH").containsEntry("includedInLedger", true)
                .containsEntry("holderWarning", false);
        assertThat(mismatch).containsEntry("holderMatch", "MISMATCH").containsEntry("includedInLedger", false)
                .containsEntry("holderWarning", true);
        assertThat(unknown).containsEntry("holderMatch", "UNKNOWN").containsEntry("includedInLedger", true);

        long mismatchId = ((Number) mismatch.get("id")).longValue();
        assertThat(transactionDao.findByAccountIdAndExternalId(mismatchId, "T-2"))
                .as("an excluded MISMATCH account is not ingested").isEmpty();

        // Including a MISMATCH account needs a reason (R3.8).
        assertThat(status(patch("/api/bank/accounts/" + mismatchId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"includedInLedger\":true}"))).isEqualTo(400);
        String overridden = body(perform(patch("/api/bank/accounts/" + mismatchId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"includedInLedger\":true,\"holderOverrideReason\":\"Konto wspólnika " + runId + "\"}")), 200);
        assertThat(JsonPath.<Boolean>read(overridden, "$.includedInLedger")).isTrue();
        assertThat(JsonPath.<String>read(overridden, "$.holderMatch")).isEqualTo("MISMATCH");
        assertThat(JsonPath.<Boolean>read(overridden, "$.holderWarning")).isFalse();
        assertThat(JsonPath.<String>read(overridden, "$.holderOverrideReason")).isEqualTo("Konto wspólnika " + runId);
        assertThat(JsonPath.<String>read(overridden, "$.holderOverrideBy")).isNotBlank();

        // The overridden account is synced from now on.
        String manual = awaitRun(requestSync(connectionId));
        assertThat(JsonPath.<String>read(manual, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(transactionDao.findByAccountIdAndExternalId(mismatchId, "T-2")).isPresent();
    }

    // =============================================================================================
    // Detection + links + effects (R14.2–R14.6, R16.1–R16.6, R17.1–R17.5)
    // =============================================================================================

    @Test
    @DisplayName("sync → internal transfer AUTO, FM-{id} client payment AUTO + reconcile, payout suggestion → PAID + ledgerEntries, "
            + "procurement link + paymentStatus, rule learning + override, ignore")
    void detectionLinksAndEffects() throws Exception {
        long connectionId = createConnection();
        String mainIban = plIban();
        String savingsIban = plIban();

        // FOR-06-07 schedule with one open tranche; FOR-07-06 planned bank payout; FOR-06-03 document.
        BigDecimal trancheAmount = uniqueAmount();
        Fixture f = tx().execute(s -> {
            Fixture x = new Fixture();
            ProjectEntity project = newProject();
            x.projectId = project.getId();
            PaymentScheduleEntity schedule = new PaymentScheduleEntity();
            schedule.setProject(project);
            schedule.setCurrency("PLN");
            schedule.setBasisGross(trancheAmount);
            schedule.setSource(ScheduleSource.MANUAL);
            schedule.setStatus(ScheduleStatus.PUBLISHED);
            PaymentTrancheEntity tranche = new PaymentTrancheEntity();
            tranche.setSchedule(schedule);
            tranche.setSeqNo(1);
            tranche.setDueDate(today.plusDays(7));
            tranche.setAmountGross(trancheAmount);
            schedule.getTranches().add(tranche);
            x.scheduleId = scheduleDao.save(schedule).getId();
            return x;
        });
        BigDecimal payoutAmount = uniqueAmount();
        String workerName = "Pracownik " + runId;
        Long payoutId = tx().execute(s -> newPayout(workerName, payoutAmount).getId());
        Long workerId = tx().execute(s -> payoutDao.findById(payoutId).orElseThrow().getWorker().getId());
        BigDecimal docTotal = uniqueAmount();
        Long documentId = tx().execute(s -> newDocument(docTotal, "FV/" + runId + "/DOC", f.projectId).getId());
        BigDecimal energyAmount = uniqueAmount();
        String energy = "Energia Dostawca " + runId;

        stubStore.seed(connectionId, new StubSeed(StubSeed.Mode.REPLACE, null, null, null,
                List.of(account("MAIN", "Główne " + runId, mainIban, new BigDecimal("10000.00"), null),
                        account("SAV", "Oszczędności " + runId, savingsIban, new BigDecimal("5000.00"), null)),
                List.of(txn("MAIN", "T-INT", TxnStatus.POSTED, new BigDecimal("-1000.00"), today,
                                "Przelew własny " + runId, companyName, savingsIban),
                        txn("MAIN", "T-CLI", TxnStatus.POSTED, trancheAmount, today,
                                "Zaplata za etap FM-" + f.projectId, "Klient " + runId, null),
                        txn("MAIN", "T-PAY", TxnStatus.POSTED, payoutAmount.negate(), today,
                                "Przelew " + runId, "Odbiorca Inny " + runId, null),
                        txn("MAIN", "T-DOC", TxnStatus.POSTED, docTotal.negate(), today,
                                "Zakupy materialow " + runId, "Hurtownia Inna " + runId, null),
                        txn("MAIN", "T-EN1", TxnStatus.POSTED, energyAmount.negate(), today,
                                "Rachunek " + runId, energy, null),
                        txn("MAIN", "T-IGN", TxnStatus.POSTED, new BigDecimal("3.21"), today,
                                "Zwrot prowizji " + runId, "Bank " + runId, null))));
        completeAndAwait(connectionId);
        Long mainId = accountId(connectionId, "MAIN");
        Long savingsId = accountId(connectionId, "SAV");

        // --- internal transfer AUTO (R14.2) → confirm-auto → LINKED
        long tInt = txnId(mainId, "T-INT");
        String intDetail = detail(tInt);
        assertThat(JsonPath.<String>read(intDetail, "$.line.linkStatus")).isEqualTo("AUTO_LINKED");
        assertThat(JsonPath.<String>read(intDetail, "$.line.links[0].kind")).isEqualTo("INTERNAL_TRANSFER");
        assertThat(JsonPath.<Number>read(intDetail, "$.line.links[0].counterAccountId").longValue()).isEqualTo(savingsId);
        assertThat(JsonPath.<String>read(intDetail, "$.line.links[0].source")).isEqualTo("RULE");
        String confirmed = body(perform(post("/api/bank/transactions/" + tInt + "/confirm-auto")
                .contentType(MediaType.APPLICATION_JSON).content("{\"remember\":false}")), 200);
        assertThat(JsonPath.<String>read(confirmed, "$.linkStatus")).isEqualTo("LINKED");

        // --- FM-{projectId} client payment AUTO → PaymentTransaction CONFIRMED + reconciled tranche (R14.4, R16.6)
        long tCli = txnId(mainId, "T-CLI");
        String cliDetail = detail(tCli);
        assertThat(JsonPath.<String>read(cliDetail, "$.line.linkStatus")).isEqualTo("AUTO_LINKED");
        assertThat(JsonPath.<String>read(cliDetail, "$.line.links[0].kind")).isEqualTo("CLIENT_PAYMENT");
        assertThat(JsonPath.<Number>read(cliDetail, "$.line.links[0].paymentScheduleId").longValue()).isEqualTo(f.scheduleId);
        long paymentTxnId = JsonPath.<Number>read(cliDetail, "$.line.links[0].paymentTransactionId").longValue();
        List<PaymentTransactionEntity> payments = paymentTransactionDao.findByScheduleIdOrderByIdAsc(f.scheduleId);
        assertThat(payments).singleElement().satisfies(p -> {
            assertThat(p.getId()).isEqualTo(paymentTxnId);
            assertThat(p.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
            assertThat(p.getChannel()).isEqualTo(com.foremen.service.payment.PaymentChannel.BANK_TRANSFER);
            assertThat(p.getAmountGross()).isEqualByComparingTo(trancheAmount);
            assertThat(p.getExternalRef()).startsWith("bank-txn:" + tCli + ":");
        });
        assertThat(trancheDao.findByScheduleIdOrderBySeqNoAsc(f.scheduleId)).singleElement().satisfies(t -> {
            assertThat(t.getPaidGross()).isEqualByComparingTo(trancheAmount);
            assertThat(t.getStatus()).isEqualTo(TrancheStatus.PAID);
        });

        // --- payout suggestion → confirm → PAID + ledgerEntries (R14.5, R16.1, R16.3)
        long tPay = txnId(mainId, "T-PAY");
        String payDetail = detail(tPay);
        assertThat(JsonPath.<String>read(payDetail, "$.line.linkStatus")).isEqualTo("SUGGESTED");
        assertThat(JsonPath.<String>read(payDetail, "$.suggestions[0].kind")).isEqualTo("WORKER_PAYOUT");
        assertThat(JsonPath.<Number>read(payDetail, "$.suggestions[0].workerPayoutId").longValue()).isEqualTo(payoutId);
        long suggestionId = JsonPath.<Number>read(payDetail, "$.suggestions[0].id").longValue();
        assertThat(payoutDao.findById(payoutId).orElseThrow().getStatus()).as("a suggestion has no effect").isEqualTo(PayoutStatus.PLANNED);
        String payConfirmed = body(perform(post("/api/bank/suggestions/" + suggestionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"remember\":false}")), 200);
        assertThat(JsonPath.<String>read(payConfirmed, "$.linkStatus")).isEqualTo("LINKED");
        String payouts = body(perform(get("/api/worker-payouts").param("workerId", String.valueOf(workerId))), 200);
        assertThat(JsonPath.<String>read(payouts, "$[0].status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(payouts, "$[0].paymentChannel")).isEqualTo("BANK_TRANSFER");
        assertThat(JsonPath.<List<Number>>read(payouts, "$[0].ledgerEntries[*].transactionId"))
                .extracting(Number::longValue).containsExactly(tPay);
        assertThat(JsonPath.<String>read(payouts, "$[0].ledgerEntries[0].channel")).isEqualTo("BANK");

        // --- procurement link + paymentStatus (R16.4, R18.3): half → PARTIALLY_PAID, replace with full → PAID
        long tDoc = txnId(mainId, "T-DOC");
        BigDecimal half = docTotal.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.DOWN);
        String linked = body(perform(post("/api/bank/transactions/" + tDoc + "/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"PROCUREMENT\",\"amount\":" + half + ",\"procurementDocumentId\":" + documentId
                        + ",\"remember\":false}")), 201);
        long docLinkId = JsonPath.<Number>read(linked, "$.linkId").longValue();
        assertThat(JsonPath.<String>read(linked, "$.linkStatus")).isEqualTo("PARTIALLY_LINKED");
        assertThat(JsonPath.<String>read(body(perform(get("/api/procurement-documents/" + documentId)), 200), "$.paymentStatus"))
                .isEqualTo("PARTIALLY_PAID");
        String replaced = body(perform(put("/api/bank/links/" + docLinkId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"PROCUREMENT\",\"amount\":" + docTotal + ",\"procurementDocumentId\":" + documentId
                        + ",\"remember\":false}")), 200);
        assertThat(JsonPath.<String>read(replaced, "$.linkStatus")).isEqualTo("LINKED");
        String doc = body(perform(get("/api/procurement-documents/" + documentId)), 200);
        assertThat(JsonPath.<String>read(doc, "$.paymentStatus")).isEqualTo("PAID");
        assertThat(JsonPath.<List<Number>>read(doc, "$.ledgerEntries[*].transactionId"))
                .extracting(Number::longValue).containsExactly(tDoc);

        // --- rule learning (R17.3): a manual link with remember=true learns the counterparty rule
        List<UnplannedExpenseCategoryEntity> categories = new ArrayList<>();
        categoryDao.findAll().forEach(categories::add);
        assertThat(categories).as("seeded unplanned categories").hasSizeGreaterThanOrEqualTo(2);
        long c1 = categories.get(0).getId();
        long c2 = categories.get(1).getId();
        long tEn1 = txnId(mainId, "T-EN1");
        assertThat(JsonPath.<String>read(detail(tEn1), "$.line.linkStatus")).isEqualTo("UNLINKED");
        body(perform(post("/api/bank/transactions/" + tEn1 + "/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"OTHER_EXPENSE\",\"amount\":" + energyAmount + ",\"unplannedCategoryId\":" + c1
                        + ",\"remember\":true}")), 201);
        String key = transactionDao.findById(tEn1).orElseThrow().getCounterpartyKey();
        assertThat(key).isNotBlank();
        assertThat(ruleCategory(key)).as("learned rule → category 1").isEqualTo(c1);

        // --- ignore (R17.5): reason required
        long tIgn = txnId(mainId, "T-IGN");
        assertThat(status(post("/api/bank/transactions/" + tIgn + "/ignore").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))).isEqualTo(400);
        String ignored = body(perform(post("/api/bank/transactions/" + tIgn + "/ignore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Prowizja bankowa " + runId + "\"}")), 200);
        assertThat(JsonPath.<String>read(ignored, "$.linkStatus")).isEqualTo("IGNORED");

        // --- next sync: the learned rule auto-links a new line of the same counterparty; old lines untouched
        stubStore.seed(connectionId, new StubSeed(StubSeed.Mode.APPEND, null, null, null, List.of(),
                List.of(txn("MAIN", "T-EN2", TxnStatus.POSTED, uniqueAmount().negate(), today,
                        "Rachunek kolejny " + runId, energy, null))));
        String second = awaitRun(requestSync(connectionId));
        assertThat(JsonPath.<String>read(second, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(second, "$.transactionsNew")).isEqualTo(1);
        long tEn2 = txnId(mainId, "T-EN2");
        String en2 = detail(tEn2);
        assertThat(JsonPath.<String>read(en2, "$.line.linkStatus")).isEqualTo("AUTO_LINKED");
        assertThat(JsonPath.<String>read(en2, "$.line.links[0].kind")).isEqualTo("OTHER_EXPENSE");
        assertThat(JsonPath.<String>read(en2, "$.line.links[0].source")).isEqualTo("RULE");
        assertThat(JsonPath.<Number>read(en2, "$.line.links[0].unplannedCategoryId").longValue()).isEqualTo(c1);
        assertThat(JsonPath.<String>read(detail(tInt), "$.line.linkStatus")).as("re-sync keeps links").isEqualTo("LINKED");
        assertThat(JsonPath.<String>read(detail(tIgn), "$.line.linkStatus")).isEqualTo("IGNORED");

        // --- override the auto link (R17.1) with remember → the rule follows the override (R17.3)
        long en2LinkId = JsonPath.<Number>read(en2, "$.line.links[0].id").longValue();
        BigDecimal en2Amount = transactionDao.findById(tEn2).orElseThrow().getAmount().negate();
        String overridden = body(perform(put("/api/bank/links/" + en2LinkId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"OTHER_EXPENSE\",\"amount\":" + en2Amount + ",\"unplannedCategoryId\":" + c2
                        + ",\"remember\":true}")), 200);
        assertThat(JsonPath.<String>read(overridden, "$.linkStatus")).isEqualTo("LINKED");
        assertThat(JsonPath.<String>read(detail(tEn2), "$.line.links[0].source")).isEqualTo("MANUAL");
        assertThat(ruleCategory(key)).as("rule re-learned from the override → category 2").isEqualTo(c2);
    }

    // =============================================================================================
    // Re-sync idempotence, pending replacement, balance gap (R6.1–R6.3, R7.1–R7.3)
    // =============================================================================================

    @Test
    @DisplayName("re-sync is idempotent, PENDING is replaced wholesale (promotion in place), a balance mismatch records a gap")
    void resyncPendingAndBalanceGap() throws Exception {
        long connectionId = createConnection();
        String iban = plIban();
        LocalDate fiveDaysAgo = today.minusDays(5);
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        BigDecimal opening = new BigDecimal("2000.00");

        List<StubSeed.Transaction> posted = List.of(
                txn("ACC", "P-1", TxnStatus.POSTED, new BigDecimal("-100.00"), fiveDaysAgo, "Oplata " + runId, "Firma A " + runId, null),
                txn("ACC", "P-2", TxnStatus.POSTED, new BigDecimal("50.00"), fiveDaysAgo, "Wplata " + runId, "Firma B " + runId, null));
        StubSeed.Transaction q0 = txn("ACC", "Q-0", TxnStatus.PENDING, new BigDecimal("-7.00"), null, "Oczekuje 0 " + runId, "Firma C " + runId, null);
        StubSeed.Transaction q1 = txn("ACC", "Q-1", TxnStatus.PENDING, new BigDecimal("-30.00"), null, "Oczekuje 1 " + runId, "Firma D " + runId, null);
        List<StubSeed.Transaction> first = new ArrayList<>(posted);
        first.add(q0);
        first.add(q1);
        stubStore.seed(connectionId, new StubSeed(StubSeed.Mode.REPLACE, null, null, null,
                List.of(account("ACC", "Rachunek " + runId, iban, opening, threeDaysAgo)), first));
        String run1 = completeAndAwait(connectionId);
        assertThat(JsonPath.<Integer>read(run1, "$.transactionsNew")).isEqualTo(2);
        Long accountId = accountId(connectionId, "ACC");
        assertThat(statuses(accountId)).containsEntry("P-1", BankTxnStatus.POSTED).containsEntry("P-2", BankTxnStatus.POSTED)
                .containsEntry("Q-0", BankTxnStatus.PENDING).containsEntry("Q-1", BankTxnStatus.PENDING).hasSize(4);
        long p1 = txnId(accountId, "P-1");
        long q1Id = txnId(accountId, "Q-1");
        body(perform(post("/api/bank/transactions/" + p1 + "/ignore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"test " + runId + "\"}")), 200);

        // Re-sync with unchanged provider data: nothing new, nothing duplicated, review state kept (R6.1).
        String run2 = awaitRun(requestSync(connectionId));
        assertThat(JsonPath.<String>read(run2, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(run2, "$.transactionsNew")).isZero();
        assertThat(statuses(accountId)).hasSize(4);
        assertThat(transactionDao.findById(p1).orElseThrow().getLinkStatus().name()).isEqualTo("IGNORED");
        assertThat(gaps(accountId)).as("continuous balance → no gap").isEmpty();

        // Provider: Q-1 posted yesterday (promotion), Q-0 dropped, Q-2 new pending; the balance is off by 777.
        List<StubSeed.Transaction> third = new ArrayList<>(posted);
        third.add(txn("ACC", "Q-1", TxnStatus.POSTED, new BigDecimal("-30.00"), today.minusDays(1), "Oczekuje 1 " + runId, "Firma D " + runId, null));
        third.add(txn("ACC", "Q-2", TxnStatus.PENDING, new BigDecimal("-12.00"), null, "Oczekuje 2 " + runId, "Firma E " + runId, null));
        BigDecimal wrongBalance = opening.subtract(new BigDecimal("30.00")).add(new BigDecimal("777.00"));
        stubStore.seed(connectionId, new StubSeed(StubSeed.Mode.REPLACE, null, null, null,
                List.of(account("ACC", "Rachunek " + runId, iban, wrongBalance, Instant.now())), third));
        String run3 = awaitRun(requestSync(connectionId));
        assertThat(JsonPath.<String>read(run3, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(statuses(accountId)).containsEntry("Q-1", BankTxnStatus.POSTED).containsEntry("Q-2", BankTxnStatus.PENDING)
                .doesNotContainKey("Q-0").hasSize(4);
        assertThat(txnId(accountId, "Q-1")).as("promoted in place").isEqualTo(q1Id);

        String gapList = body(perform(get("/api/bank/accounts/" + accountId + "/balance-gaps")), 200);
        assertThat(JsonPath.<List<Object>>read(gapList, "$")).hasSize(1);
        assertThat(new BigDecimal(JsonPath.read(gapList, "$[0].difference").toString())).isEqualByComparingTo("777.00");
        assertThat(JsonPath.<Boolean>read(gapList, "$[0].hasBalanceGap")).isTrue();
        long gapId = JsonPath.<Number>read(gapList, "$[0].id").longValue();
        String ack = body(perform(post("/api/bank/balance-gaps/" + gapId + "/acknowledge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"sprawdzone " + runId + "\"}")), 200);
        assertThat(JsonPath.<Boolean>read(ack, "$.hasBalanceGap")).isFalse();
        assertThat(JsonPath.<String>read(ack, "$.acknowledgedBy")).isNotBlank();
    }

    // =============================================================================================
    // helpers — HTTP
    // =============================================================================================

    private static final class Fixture {
        Long projectId;
        Long scheduleId;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth)).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request).getResponse().getStatus();
    }

    private static String body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("%s %s → %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), content)
                .isEqualTo(expectedStatus);
        return content;
    }

    private long createConnection() throws Exception {
        String created = body(perform(post("/api/bank/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"STUB\",\"providerBankCode\":\"stub_business_pl\",\"displayName\":\"Stub "
                        + runId + "-" + (++seq) + "\"}")), 201);
        assertThat(JsonPath.<String>read(created, "$.connection.status")).isEqualTo("PENDING_AUTH");
        assertThat(JsonPath.<String>read(created, "$.authorizationUrl"))
                .startsWith("http://localhost:3000/bank/connections/return?connectionId=").endsWith("stub=1");
        return JsonPath.<Number>read(created, "$.connection.id").longValue();
    }

    private String completeAndAwait(long connectionId) throws Exception {
        String completion = body(perform(post("/api/bank/connections/" + connectionId + "/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{\"stub\":\"1\"}}")), 200);
        String run = awaitRun(JsonPath.<Number>read(completion, "$.syncRunId").longValue());
        assertThat(JsonPath.<String>read(run, "$.status")).as(run).isEqualTo("SUCCEEDED");
        return run;
    }

    private long requestSync(long connectionId) throws Exception {
        String accepted = body(perform(post("/api/bank/connections/" + connectionId + "/sync")), 202);
        return JsonPath.<Number>read(accepted, "$.runId").longValue();
    }

    /** Polls the run until it leaves QUEUED / RUNNING (the run executes on {@code bankSyncExecutor}). */
    private String awaitRun(long runId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        String run = null;
        while (System.currentTimeMillis() < deadline) {
            run = body(perform(get("/api/bank/sync-runs/" + runId)), 200);
            String status = JsonPath.read(run, "$.status");
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("sync run " + runId + " did not finish: " + run);
    }

    private String detail(long transactionId) throws Exception {
        return body(perform(get("/api/bank/transactions/" + transactionId)), 200);
    }

    private static Map<String, Object> account(String connectionDetail, String namePrefix) {
        List<Map<String, Object>> accounts = JsonPath.read(connectionDetail, "$.accounts");
        return accounts.stream().filter(a -> String.valueOf(a.get("name")).startsWith(namePrefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no account " + namePrefix + " in " + connectionDetail));
    }

    // =============================================================================================
    // helpers — data
    // =============================================================================================

    private Long accountId(long connectionId, String externalId) {
        return accountDao.findByConnectionIdAndExternalId(connectionId, externalId).map(BankAccountEntity::getId).orElseThrow();
    }

    private long txnId(Long accountId, String externalId) {
        return transactionDao.findByAccountIdAndExternalId(accountId, externalId).map(BankTransactionEntity::getId)
                .orElseThrow(() -> new AssertionError("no line " + externalId));
    }

    private Map<String, BankTxnStatus> statuses(Long accountId) {
        Map<String, BankTxnStatus> out = new java.util.TreeMap<>();
        for (BankTxnStatus st : BankTxnStatus.values()) {
            transactionDao.findByAccountIdAndStatus(accountId, st).forEach(t -> out.put(t.getExternalId(), t.getStatus()));
        }
        return out;
    }

    private List<Object> gaps(Long accountId) throws Exception {
        return JsonPath.read(body(perform(get("/api/bank/accounts/" + accountId + "/balance-gaps")), 200), "$");
    }

    private Long ruleCategory(String counterpartyKey) {
        return tx().execute(s -> ruleDao.findByCounterpartyKey(counterpartyKey)
                .map(r -> r.getUnplannedCategory() == null ? null : r.getUnplannedCategory().getId())
                .orElse(null));
    }

    private static StubSeed.Account account(String externalId, String name, String iban, BigDecimal balance,
                                            Instant balanceAt) {
        return new StubSeed.Account(externalId, name, "account", iban, null, "PLN", balance, balance, null, balanceAt);
    }

    private static StubSeed.Transaction txn(String account, String externalId, TxnStatus status, BigDecimal amount,
                                            LocalDate booking, String title, String counterparty, String counterpartyIban) {
        LocalDate op = booking == null ? LocalDate.now(ZoneOffset.UTC) : booking;
        return new StubSeed.Transaction(account, externalId, status, amount, "PLN", op,
                status == TxnStatus.POSTED ? booking : null, title, counterparty, counterpartyIban, null, false);
    }

    /** A random amount in 1000.00..9999.99, practically unique within a run. */
    private BigDecimal uniqueAmount() {
        return BigDecimal.valueOf(100_000 + random.nextInt(899_999), 2);
    }

    /** A random, checksum-valid Polish IBAN. */
    private String plIban() {
        StringBuilder bban = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            bban.append(random.nextInt(10));
        }
        // PL → 25 21; check digits = 98 − (bban + "252100") mod 97.
        int check = 98 - new BigInteger(bban + "252100").mod(BigInteger.valueOf(97)).intValue();
        return "PL" + String.format("%02d", check) + bban;
    }

    private UserEntity newUser(String name, String roleCode) {
        int n = ++seq;
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail("bank-e2e+" + runId.toLowerCase(Locale.ROOT) + "-" + n + "@example.com");
        user.setRole(roleDao.findByCode(roleCode).orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    private ProjectEntity newProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("Bank E2E project " + runId + "-" + (++seq));
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private WorkerPayoutEntity newPayout(String workerName, BigDecimal amount) {
        WorkerPayoutEntity payout = new WorkerPayoutEntity();
        payout.setWorker(newUser(workerName, "WORKER"));
        payout.setPayoutDate(today);
        payout.setPeriodFrom(today.minusDays(7));
        payout.setPeriodTo(today.minusDays(1));
        payout.setAmount(amount);
        payout.setSuggestedAmount(amount);
        payout.setPaymentChannel(PaymentChannel.BANK_TRANSFER);
        payout.setStatus(PayoutStatus.PLANNED);
        return payoutDao.save(payout);
    }

    private ProcurementDocumentEntity newDocument(BigDecimal total, String invoiceNumber, Long projectId) {
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(projectDao.findById(projectId).orElseThrow());
        document.setUploadedBy(newUser("Uploader " + runId, "ADMIN"));
        document.setRawObjectKey("raw/" + runId + "/" + (++seq));
        document.setSourceMimeType("application/pdf");
        document.setStatus(ProcessingStatus.PARSED);
        document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
        document.setDocumentTotal(total);
        document.setInvoiceNumber(invoiceNumber);
        document.setInvoiceDate(today);
        document.setSupplierName("Dostawca Materialow " + runId);
        return documentDao.save(document);
    }
}
