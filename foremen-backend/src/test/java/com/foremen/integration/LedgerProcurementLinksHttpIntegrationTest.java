package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.procurement.externalorder.ExternalOrderLifecycleService;
import com.foremen.service.procurement.externalorder.ExternalOrderService;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import com.foremen.service.procurement.proforma.ClientProformaService;
import com.foremen.service.procurement.proforma.ProformaIssueService;
import com.foremen.service.procurement.proforma.ProformaStatus;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-03 task 15.3 (R7.1, R7.6, R8.1, R16.3, R28.2): the ledger link endpoints
 * ({@code POST /api/bank/transactions/{id}/links}, {@code PUT /api/bank/links/{id}},
 * {@code DELETE /api/bank/links/{id}}) driven through MockMvc against the real Liquibase schema in a
 * Testcontainers PostgreSQL. Proformas and orders are prepared through the services; every link
 * operation goes over HTTP as a FINANCIER or ADMIN principal and the outcome is verified through the
 * repositories.
 *
 * <p>Repeatability: a fresh container per run and a per-run id in every name, e-mail and code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@Import({InMemoryImageStorageConfig.class, MockMvcSecurityConfig.class})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-11-03, task 15.3: ledger links over HTTP")
class LedgerProcurementLinksHttpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (LedgerProcurementLinksHttpIntegrationTest.class) {
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

    private static final Pattern LINK_ID = Pattern.compile("\"linkId\"\\s*:\\s*(\\d+)");
    private static final String QTY = "5.000";
    private static final String SALE = "100.00";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientProformaService proformaService;
    @Autowired private ProformaIssueService issueService;
    @Autowired private ExternalOrderService orderService;
    @Autowired private ExternalOrderLifecycleService lifecycle;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private ClientProformaDao proformaDao;
    @Autowired private CompanyProfileDao companyProfileDao;
    @Autowired private ExternalOrderDao orderDao;
    @Autowired private MaterialSellerDao sellerDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private PurchasePositionDao positionDao;
    @Autowired private RoleDao roleDao;
    @Autowired private UserDao userDao;
    @Autowired private VatRateDao vatRateDao;
    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager em;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int seq;

    private static FinishingMaterialEntity catalogMaterial;
    private static VatRateEntity vat23;
    private static VatRateEntity vat8;
    private static MeasurementUnitEntity unit;

    private UserEntity admin;
    private UserEntity financier;
    private UserEntity buyerUser;

    @BeforeEach
    void setUp() {
        tx().executeWithoutResult(s -> {
            ensureCatalog();
            ensureCompanyProfile();
        });
        admin = tx().execute(s -> newUser("ADMIN", "admin"));
        financier = tx().execute(s -> newUser("FINANCIER", "fin"));
        buyerUser = tx().execute(s -> newUser("BUYER", "buyer"));
        asAdmin();
    }

    /** The proforma with its payment row initialised (status is delegated to the lazy payment). */
    private ClientProformaEntity loadProforma(Long id) {
        return tx().execute(s -> {
            ClientProformaEntity e = proformaDao.findById(id).orElseThrow();
            e.getStatus();
            return e;
        });
    }

    /** Service-level setup (create / issue / send) runs as ADMIN; the link calls carry their own principal. */
    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getId(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================================
    // (1) PROFORMA_PAYMENT: partial, replace, full -> PAID + released; released link is locked
    // =========================================================================================

    @Test
    void proformaPaymentLinksPartialReplaceFullReleaseAndLockedDelete() throws Exception {
        ProjectEntity project = newProject();
        PurchasePositionEntity p1 = position(project, PositionStatus.ACCEPTED);
        PurchasePositionEntity p2 = position(project, PositionStatus.ACCEPTED);
        ClientProformaServiceExtendedModel draft = createDraft(project, p1, p2);
        Long proformaId = draft.getId();
        BigDecimal gross = issueService.issue(proformaId, "pl").getTotalGross();
        BigDecimal part = gross.divide(new BigDecimal("3"), 2, RoundingMode.DOWN);
        BigDecimal replaced = part.add(new BigDecimal("1.00"));

        // partial link (FINANCIER)
        Long txn1 = tx().execute(s -> newTransaction("PLN", gross.add(BigDecimal.ONE)).getId());
        Long link1 = createLink(txn1, "PROFORMA_PAYMENT", part, "clientProformaId", proformaId, financier,
                "FINANCIER", 201);
        ClientProformaEntity afterPartial = loadProforma(proformaId);
        assertThat(afterPartial.getStatus()).isEqualTo(ProformaStatus.PARTIALLY_PAID);
        assertThat(afterPartial.getPaidGross()).isEqualByComparingTo(part);
        assertThat(afterPartial.getReleasedAt()).isNull();
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.IN_PROFORMA);

        // staff detail lists the payment (FOR-11-03 15.1: BankTransactionLinkDao.findProformaPaymentsByClientProformaId);
        // read through the service: the unified /api/client-payments view does not carry the ledger payments
        ClientProformaServiceExtendedModel detail = proformaService.findById(proformaId);
        assertThat(detail.getPayments()).singleElement().satisfies(pay -> {
            assertThat(pay.linkId()).isEqualTo(link1);
            assertThat(pay.amount()).isEqualByComparingTo(part);
        });
        // replace the amount (ADMIN): still PARTIALLY_PAID with the new paidGross
        Long link1b = replaceLink(link1, "PROFORMA_PAYMENT", replaced, "clientProformaId", proformaId, admin,
                "ADMIN", 200);
        ClientProformaEntity afterReplace = loadProforma(proformaId);
        assertThat(afterReplace.getStatus()).isEqualTo(ProformaStatus.PARTIALLY_PAID);
        assertThat(afterReplace.getPaidGross()).isEqualByComparingTo(replaced);
        assertThat(linkDao.findById(link1b).orElseThrow().getAmount()).isEqualByComparingTo(replaced);

        // the remainder over a second line: PAID, released, positions WITH_BUYER
        BigDecimal rest = gross.subtract(replaced);
        Long txn2 = tx().execute(s -> newTransaction("PLN", rest).getId());
        Long link2 = createLink(txn2, "PROFORMA_PAYMENT", rest, "clientProformaId", proformaId, financier,
                "FINANCIER", 201);
        ClientProformaEntity paid = loadProforma(proformaId);
        assertThat(paid.getStatus()).isEqualTo(ProformaStatus.PAID);
        assertThat(paid.getPaidGross()).isEqualByComparingTo(gross);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getReleasedAt()).isNotNull();
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(statusOf(p2)).isEqualTo(PositionStatus.WITH_BUYER);

        // an order of the released position references the proforma through its still-active line
        // (FOR-11-03 15.1: ClientProformaLineDao.findActiveWithProformaByPositionIds)
        ExternalOrderServiceExtendedModel order = sentOrder(project, newSeller(), p2);
        String orderJson = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/external-orders/" + order.getId()).with(as(buyerUser, "BUYER")), 200);
        assertThat(orderJson).contains("\"proformas\":[{\"id\":" + proformaId + ",");
        assertThat(orderJson).contains("\"status\":\"PAID\"");

        // released: DELETE and PUT are locked (409) and nothing changes
        call(delete("/api/bank/links/" + link2).with(as(financier, "FINANCIER")), 409);
        call(put("/api/bank/links/" + link2).with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFORMA_PAYMENT", rest.subtract(BigDecimal.ONE), "clientProformaId", proformaId)),
                409);
        assertThat(linkDao.findById(link2)).isPresent();
        assertThat(linkDao.findById(link2).orElseThrow().getAmount()).isEqualByComparingTo(rest);
        assertThat(loadProforma(proformaId).getStatus()).isEqualTo(ProformaStatus.PAID);
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.WITH_BUYER);
    }

    // =========================================================================================
    // (2) EXTERNAL_ORDER_PAYMENT: full -> PAID/LEDGER; smaller replace and delete -> SENT_FOR_PAYMENT
    // =========================================================================================

    @Test
    void externalOrderPaymentLinkPaidReplaceSmallerAndDeleteRevertToSentForPayment() throws Exception {
        ProjectEntity project = newProject();
        MaterialSellerEntity seller = newSeller();
        PurchasePositionEntity p = position(project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(project, seller, p);
        Long orderId = order.getId();
        BigDecimal payable = orderService.findById(orderId).getPayableGross();
        BigDecimal smaller = payable.subtract(new BigDecimal("10.00"));

        Long txn = tx().execute(s -> newTransaction("PLN", payable.negate()).getId());
        Long linkId = createLink(txn, "EXTERNAL_ORDER_PAYMENT", payable, "externalOrderId", orderId, financier,
                "FINANCIER", 201);
        ExternalOrderEntity paid = orderDao.findById(orderId).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(paid.getPaidSource()).isEqualTo(PaidSource.LEDGER);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);

        // replace with a smaller amount: the ledger no longer covers the payable
        Long replacedId = replaceLink(linkId, "EXTERNAL_ORDER_PAYMENT", smaller, "externalOrderId", orderId,
                financier, "FINANCIER", 200);
        ExternalOrderEntity partial = orderDao.findById(orderId).orElseThrow();
        assertThat(partial.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(partial.getPaidSource()).isNull();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);
        assertThat(linkDao.findById(replacedId).orElseThrow().getAmount()).isEqualByComparingTo(smaller);

        // top it up to the payable over the same line, then delete: back to SENT_FOR_PAYMENT
        Long topUp = createLink(txn, "EXTERNAL_ORDER_PAYMENT", new BigDecimal("10.00"), "externalOrderId", orderId,
                admin, "ADMIN", 201);
        assertThat(orderDao.findById(orderId).orElseThrow().getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        call(delete("/api/bank/links/" + topUp).with(as(admin, "ADMIN")), 200);
        call(delete("/api/bank/links/" + replacedId).with(as(financier, "FINANCIER")), 200);
        assertThat(linkDao.findById(topUp)).isEmpty();
        assertThat(linkDao.findById(replacedId)).isEmpty();
        ExternalOrderEntity reverted = orderDao.findById(orderId).orElseThrow();
        assertThat(reverted.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(reverted.getPaidSource()).isNull();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);
    }

    @Test
    void deletingTheOnlyFullLinkOfAnOrderRevertsItToSentForPayment() throws Exception {
        ProjectEntity project = newProject();
        PurchasePositionEntity p = position(project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(project, newSeller(), p);
        BigDecimal payable = orderService.findById(order.getId()).getPayableGross();
        Long txn = tx().execute(s -> newTransaction("PLN", payable.negate()).getId());
        Long linkId = createLink(txn, "EXTERNAL_ORDER_PAYMENT", payable, "externalOrderId", order.getId(),
                financier, "FINANCIER", 201);
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(ExternalOrderStatus.PAID);

        call(delete("/api/bank/links/" + linkId).with(as(financier, "FINANCIER")), 200);

        assertThat(linkDao.findById(linkId)).isEmpty();
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);
    }

    // =========================================================================================
    // (3) rejected links: direction (400, R13.2), currency / not linkable / over the remainder (409)
    // =========================================================================================

    @Test
    void rejectedLinksLeaveNoRowsAndNoStateChange() throws Exception {
        ProjectEntity project = newProject();
        MaterialSellerEntity seller = newSeller();
        PurchasePositionEntity pp = position(project, PositionStatus.ACCEPTED);
        ClientProformaServiceExtendedModel draft = createDraft(project, pp);
        Long proformaId = draft.getId();
        BigDecimal ten = new BigDecimal("10.00");

        // DRAFT proforma: not linkable
        Long credit = tx().execute(s -> newTransaction("PLN", new BigDecimal("50.00")).getId());
        call(post("/api/bank/transactions/" + credit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFORMA_PAYMENT", ten, "clientProformaId", proformaId)), 409);
        assertThat(linkDao.findByTransactionIdOrderByIdAsc(credit)).isEmpty();

        BigDecimal gross = issueService.issue(proformaId, "pl").getTotalGross();

        // wrong direction: a debit line cannot pay a proforma; a credit line cannot pay an order
        Long debit = tx().execute(s -> newTransaction("PLN", new BigDecimal("-50.00")).getId());
        call(post("/api/bank/transactions/" + debit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFORMA_PAYMENT", ten, "clientProformaId", proformaId)), 400);
        PurchasePositionEntity wp = position(project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(project, seller, wp);
        call(post("/api/bank/transactions/" + credit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXTERNAL_ORDER_PAYMENT", ten, "externalOrderId", order.getId())), 400);

        // currency: PLN only
        Long eurCredit = tx().execute(s -> newTransaction("EUR", new BigDecimal("50.00")).getId());
        call(post("/api/bank/transactions/" + eurCredit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFORMA_PAYMENT", ten, "clientProformaId", proformaId)), 409);
        Long eurDebit = tx().execute(s -> newTransaction("EUR", new BigDecimal("-50.00")).getId());
        call(post("/api/bank/transactions/" + eurDebit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXTERNAL_ORDER_PAYMENT", ten, "externalOrderId", order.getId())), 409);

        // over the proforma remainder (the line itself is large enough): 409 targetOverLinked
        Long big = tx().execute(s -> newTransaction("PLN", gross.add(new BigDecimal("500.00"))).getId());
        call(post("/api/bank/transactions/" + big + "/links").with(as(admin, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PROFORMA_PAYMENT", gross.add(BigDecimal.ONE), "clientProformaId", proformaId)), 409);

        // over the order payable
        BigDecimal payable = orderService.findById(order.getId()).getPayableGross();
        Long bigDebit = tx().execute(s -> newTransaction("PLN", payable.add(new BigDecimal("500.00")).negate())
                .getId());
        call(post("/api/bank/transactions/" + bigDebit + "/links").with(as(admin, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXTERNAL_ORDER_PAYMENT", payable.add(BigDecimal.ONE), "externalOrderId",
                        order.getId())), 409);

        // a DRAFT order is not linkable
        PurchasePositionEntity dp = position(project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel draftOrder = draftOrder(project, seller);
        orderService.replaceLines(draftOrder.getId(), List.of(line(dp, QTY, "80.00")));
        Long okDebit = tx().execute(s -> newTransaction("PLN", new BigDecimal("-50.00")).getId());
        call(post("/api/bank/transactions/" + okDebit + "/links").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EXTERNAL_ORDER_PAYMENT", ten, "externalOrderId", draftOrder.getId())), 409);

        // nothing was written and nothing moved
        for (Long id : List.of(debit, eurCredit, eurDebit, big, bigDebit, okDebit)) {
            assertThat(linkDao.findByTransactionIdOrderByIdAsc(id)).as("links of %s", id).isEmpty();
        }
        assertThat(linkDao.findByTransactionIdOrderByIdAsc(credit)).isEmpty();
        ClientProformaEntity stored = loadProforma(proformaId);
        assertThat(stored.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        assertThat(stored.getPaidGross()).isEqualByComparingTo("0");
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(orderDao.findById(draftOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.DRAFT);
    }

    // =========================================================================================
    // HTTP helpers
    // =========================================================================================

    private Long createLink(Long txnId, String kind, BigDecimal amount, String targetField, Long targetId,
                            UserEntity user, String role, int expected) throws Exception {
        String response = call(post("/api/bank/transactions/" + txnId + "/links").with(as(user, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(kind, amount, targetField, targetId)), expected);
        return linkId(response);
    }

    private Long replaceLink(Long linkId, String kind, BigDecimal amount, String targetField, Long targetId,
                             UserEntity user, String role, int expected) throws Exception {
        String response = call(put("/api/bank/links/" + linkId).with(as(user, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(kind, amount, targetField, targetId)), expected);
        return linkId(response);
    }

    private static String body(String kind, BigDecimal amount, String targetField, Long targetId) {
        return "{\"kind\":\"" + kind + "\",\"amount\":" + amount.toPlainString() + ",\"" + targetField + "\":"
                + targetId + ",\"remember\":false}";
    }

    private String call(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult r;
        try {
            r = mockMvc.perform(request).andReturn();
        } finally {
            // the request post-processor leaves the caller's principal in the thread; setup runs as ADMIN
            asAdmin();
        }
        String content = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as("%s -> %s", request, content).isEqualTo(expected);
        return content;
    }

    private static Long linkId(String json) {
        Matcher m = LINK_ID.matcher(json);
        assertThat(m.find()).as("linkId in %s", json).isTrue();
        return Long.valueOf(m.group(1));
    }

    private static RequestPostProcessor as(UserEntity user, String roleCode) {
        // The JWT filter's shape: principal = the numeric user id (Long), authority ROLE_<code>.
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    // =========================================================================================
    // fixtures (copied from ProformaExternalOrderFlowsIntegrationTest)
    // =========================================================================================

    private ProjectEntity newProject() {
        return tx().execute(s -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("FOR1103H " + runId + "-" + (++seq));
            project.setStatus(ProjectStatus.ACTIVE);
            return projectDao.save(project);
        });
    }

    private MaterialSellerEntity newSeller() {
        return tx().execute(s -> {
            MaterialSellerEntity seller = new MaterialSellerEntity();
            seller.setCode("FOR1103H-" + runId + "-" + (++seq));
            seller.setNameRU("Продавец " + runId + seq);
            seller.setNamePL("Sprzedawca " + runId + seq);
            return sellerDao.save(seller);
        });
    }

    private void ensureCatalog() {
        if (catalogMaterial != null) {
            return;
        }
        String u = runId;
        MaterialCategoryEntity category = new MaterialCategoryEntity();
        category.setCode("f1103h_cat_" + u);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        em.persist(category);
        MaterialEntity material = new MaterialEntity();
        material.setCode("f1103h_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        em.persist(material);
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode("f1103h_type_" + u);
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        em.persist(type);
        unit = new MeasurementUnitEntity();
        unit.setCode("f1103h_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        em.persist(unit);
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode("f1103h_pkg_" + u);
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет");
        pkg.setNamePL("Pakiet");
        pkg.setActive(true);
        em.persist(pkg);
        vat23 = vat("23");
        vat8 = vat("8");
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setCategory(category);
        m.setMaterial(material);
        m.setType(type);
        m.setUnit(unit);
        m.setPackages(new HashSet<>(Set.of(pkg)));
        m.setModel("Model " + u);
        m.setRetailNet(new BigDecimal("10.00"));
        m.setCostNet(BigDecimal.ZERO);
        m.setActive(true);
        em.persist(m);
        em.flush();
        catalogMaterial = m;
    }

    private VatRateEntity vat(String rate) {
        for (VatRateEntity existing : vatRateDao.findAll()) {
            if (existing.isActive() && existing.getRate() != null
                    && existing.getRate().compareTo(new BigDecimal(rate)) == 0) {
                return existing;
            }
        }
        VatRateEntity v = new VatRateEntity();
        v.setCode("f1103h_vat_" + rate + "_" + runId);
        v.setRate(new BigDecimal(rate));
        v.setNameRU(rate + "%");
        v.setNamePL(rate + "%");
        v.setActive(true);
        em.persist(v);
        return v;
    }

    private void ensureCompanyProfile() {
        if (companyProfileDao.count() > 0) {
            CompanyProfileEntity existing = companyProfileDao.findAll().iterator().next();
            if (existing.getBankAccount() != null && !existing.getBankAccount().isBlank()) {
                return;
            }
            existing.setFullName("Foremen Test Sp. z o.o.");
            existing.setRegisteredAddress("ul. Testowa 1, Warszawa");
            existing.setNip("5252525252");
            existing.setBankAccount("PL61109010140000071219812874");
            companyProfileDao.save(existing);
            return;
        }
        CompanyProfileEntity p = new CompanyProfileEntity();
        p.setFullName("Foremen Test Sp. z o.o.");
        p.setRegisteredAddress("ul. Testowa 1, Warszawa");
        p.setNip("5252525252");
        p.setBankAccount("PL61109010140000071219812874");
        companyProfileDao.save(p);
    }

    private PurchasePositionEntity position(ProjectEntity project, PositionStatus status) {
        return tx().execute(s -> {
            PurchasePositionEntity pos = new PurchasePositionEntity();
            pos.setProject(project);
            pos.setKind(PositionKind.FINISHING);
            pos.setSourcingPath(SourcingPath.AGREED);
            pos.setStatus(status);
            pos.setPlannedMaterial(catalogMaterial);
            pos.setQuantity(new BigDecimal(QTY));
            pos.setUnit(unit);
            pos.setPlannedPurchasePrice(new BigDecimal("80.00"));
            pos.setSalePrice(new BigDecimal(SALE));
            pos.setPurchaseVatRate(vat23);
            pos.setSaleVatRate(vat8);
            pos.setDeliveryCostShare(BigDecimal.ZERO);
            return positionDao.save(pos);
        });
    }

    private PositionStatus statusOf(PurchasePositionEntity p) {
        return positionDao.findById(p.getId()).orElseThrow().getStatus();
    }

    private ClientProformaServiceExtendedModel createDraft(ProjectEntity project, PurchasePositionEntity... positions) {
        ClientProformaServiceExtendedModel m = new ClientProformaServiceExtendedModel();
        m.setProjectId(project.getId());
        m.setPositionIds(java.util.Arrays.stream(positions).map(PurchasePositionEntity::getId).toList());
        return proformaService.create(m);
    }

    private ExternalOrderServiceExtendedModel draftOrder(ProjectEntity project, MaterialSellerEntity seller) {
        ExternalOrderServiceExtendedModel m = new ExternalOrderServiceExtendedModel();
        m.setProjectId(project.getId());
        m.setSellerId(seller.getId());
        m.setAssignedBuyerId(buyerUser.getId());
        m.setDeliveryCostNet(BigDecimal.ZERO);
        return orderService.create(m);
    }

    /** A SENT_FOR_PAYMENT order covering the whole position at 80.00 net. */
    private ExternalOrderServiceExtendedModel sentOrder(ProjectEntity project, MaterialSellerEntity seller,
                                                       PurchasePositionEntity p) {
        ExternalOrderServiceExtendedModel order = draftOrder(project, seller);
        orderService.replaceLines(order.getId(), List.of(line(p, QTY, "80.00")));
        tx().executeWithoutResult(s -> {
            ExternalOrderEntity o = orderDao.findById(order.getId()).orElseThrow();
            o.setReferenceNumber("INV-" + runId + "-" + order.getId());
            orderDao.save(o);
        });
        lifecycle.send(order.getId());
        return order;
    }

    private static ExternalOrderLineServiceModel line(PurchasePositionEntity p, String qty, String net) {
        ExternalOrderLineServiceModel l = new ExternalOrderLineServiceModel();
        l.setPositionId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPurchaseNet(new BigDecimal(net));
        l.setAboveCeilingAcknowledged(false);
        return l;
    }

    private UserEntity newUser(String roleCode, String tag) {
        UserEntity u = new UserEntity();
        u.setName("F1103H " + tag + " " + runId);
        u.setEmail("f1103h+" + tag + "-" + runId.toLowerCase(Locale.ROOT) + "-" + (++seq) + "@example.com");
        u.setRole(roleDao.findByCode(roleCode).orElseThrow());
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("ru"));
        return userDao.save(u);
    }

    /** A MANUAL cash-ledger line in the given currency; negative amount = debit. */
    private BankTransactionEntity newTransaction(String currency, BigDecimal amount) {
        BankAccountEntity account = new BankAccountEntity();
        account.setType(BankAccountType.CASH);
        account.setName("F1103H " + runId + "-" + (++seq));
        account.setCurrency(currency);
        accountDao.save(account);

        BankTransactionEntity txn = new BankTransactionEntity();
        txn.setAccount(account);
        txn.setChannel(LedgerChannel.CASH);
        txn.setOrigin(LedgerOrigin.MANUAL);
        txn.setStatus(BankTxnStatus.POSTED);
        txn.setAmount(amount);
        txn.setCurrency(currency);
        txn.setOperationDate(today);
        txn.setBookingDate(today);
        txn.setTitle("F1103H " + runId);
        txn.setCreatedByUser("f1103h-test");
        return transactionDao.save(txn);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
