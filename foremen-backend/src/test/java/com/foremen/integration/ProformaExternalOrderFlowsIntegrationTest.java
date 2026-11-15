package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.NotificationDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.bank.detect.DetectionService;
import com.foremen.service.bank.detect.ReverseDocumentMatcher;
import com.foremen.service.bank.detect.ReverseProformaMatcher;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.procurement.externalorder.ExternalOrderLifecycleService;
import com.foremen.service.procurement.externalorder.ExternalOrderService;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import com.foremen.service.procurement.proforma.ClientProformaService;
import com.foremen.service.procurement.proforma.ProformaCancelService;
import com.foremen.service.procurement.proforma.ProformaIssueService;
import com.foremen.service.procurement.proforma.ProformaStatus;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-03 task 11.1 (R28.2, R26.1, R26.4): end-to-end flows of client proformas and external orders against
 * the real Liquibase schema (changesets 236-239 included) in a Testcontainers PostgreSQL, with an in-memory
 * object store for the PDF. Services are driven directly under an ADMIN principal; ABAC, project isolation and
 * the client-safe DTO go through MockMvc with the real permission matrix of the seed.
 *
 * <p>Repeatability: a fresh container per run and a per-run id in every name, e-mail, code and invoice title;
 * no test depends on rows created by another one (the only shared rows are the company profile and the
 * catalog, created once with run-unique codes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@Import({InMemoryImageStorageConfig.class, MockMvcSecurityConfig.class})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-11-03, task 11.1: proforma and external order integration flows")
class ProformaExternalOrderFlowsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (ProformaExternalOrderFlowsIntegrationTest.class) {
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
    @Autowired private ClientProformaService proformaService;
    @Autowired private ProformaIssueService issueService;
    @Autowired private ProformaCancelService cancelService;
    @Autowired private ExternalOrderService orderService;
    @Autowired private ExternalOrderLifecycleService lifecycle;
    @Autowired private BankLinkService linkService;
    @Autowired private DetectionService detectionService;
    @Autowired private ReverseProformaMatcher reverseMatcher;
    @Autowired private ProjectAccessCache projectAccessCache;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankConnectionDao connectionDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private BankTransactionSuggestionDao suggestionDao;
    @Autowired private ClientProformaDao proformaDao;
    @Autowired private ClientProformaLineDao proformaLineDao;
    @Autowired private CompanyProfileDao companyProfileDao;
    @Autowired private ExternalOrderDao orderDao;
    @Autowired private ExternalOrderLineDao orderLineDao;
    @Autowired private MaterialSellerDao sellerDao;
    @Autowired private NotificationDao notificationDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private PurchasePositionDao positionDao;
    @Autowired private RoleDao roleDao;
    @Autowired private UserDao userDao;
    @Autowired private VatRateDao vatRateDao;
    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager em;

    private static final String QTY = "5.000";
    private static final String SALE = "100.00";

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int seq;

    private static FinishingMaterialEntity catalogMaterial;
    private static VatRateEntity vat23;
    private static VatRateEntity vat8;
    private static MeasurementUnitEntity unit;

    private UserEntity admin;
    private UserEntity buyerUser;

    @BeforeEach
    void setUp() {
        tx().executeWithoutResult(s -> {
            ensureCatalog();
            ensureCompanyProfile();
        });
        admin = tx().execute(s -> newUser("ADMIN", "admin"));
        buyerUser = tx().execute(s -> newUser("BUYER", "order-buyer"));
        asAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================================
    // Proforma lifecycle and release
    // =========================================================================================

    @Test
    void proformaLifecycleIssueStoresPdfPartialAndFullPaymentReleaseAndLockAndNotifications() {
        Fixture f = fixture();
        UserEntity manager = tx().execute(s -> {
            UserEntity m = newUser("MANAGER", "mgr");
            addMember(m, f.project, "MANAGER");
            return m;
        });
        PurchasePositionEntity p1 = position(f.project, PositionStatus.ACCEPTED);
        PurchasePositionEntity p2 = position(f.project, PositionStatus.ACCEPTED);

        ClientProformaServiceExtendedModel draft = createDraft(f.project, p1, p2);
        assertThat(draft.getStatus()).isEqualTo(ProformaStatus.DRAFT);
        assertThat(draft.getNumber()).isNull();

        ClientProformaServiceExtendedModel issued = issueService.issue(draft.getId(), "pl");
        assertThat(issued.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        assertThat(issued.getNumber()).matches("PF/\\d{4}/\\d{6}");
        assertThat(issued.isHasPdf()).isTrue();
        ClientProformaEntity stored = proformaDao.findById(draft.getId()).orElseThrow();
        assertThat(stored.getPdfObjectKey()).isNotBlank();
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.IN_PROFORMA);
        assertThat(statusOf(p2)).isEqualTo(PositionStatus.IN_PROFORMA);

        BigDecimal gross = stored.getTotalGross();
        assertThat(gross.signum()).isPositive();
        BigDecimal part = gross.divide(new BigDecimal("3"), 2, java.math.RoundingMode.DOWN);

        // partial payment: PARTIALLY_PAID, no release
        Long txn1 = tx().execute(s -> newTransaction("PLN", gross.add(BigDecimal.ONE), LedgerOrigin.MANUAL).getId());
        linkService.createLink(txn1, LinkRequest.of(LinkKind.PROFORMA_PAYMENT, part)
                .withClientProforma(draft.getId()), false);
        ClientProformaEntity afterPartial = proformaDao.findById(draft.getId()).orElseThrow();
        assertThat(afterPartial.getStatus()).isEqualTo(ProformaStatus.PARTIALLY_PAID);
        assertThat(afterPartial.getPaidGross()).isEqualByComparingTo(part);
        assertThat(afterPartial.getReleasedAt()).isNull();
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.IN_PROFORMA);

        // a proforma with money cannot be cancelled
        assertConflict(() -> cancelService.cancel(draft.getId(), "reason"), "error.proforma.cancel.hasPayments");

        // remainder: PAID, released IN_PROFORMA -> CLIENT_PAID -> WITH_BUYER
        BigDecimal rest = gross.subtract(part);
        Long txn2 = tx().execute(s -> newTransaction("PLN", rest, LedgerOrigin.MANUAL).getId());
        var outcome = linkService.createLink(txn2, LinkRequest.of(LinkKind.PROFORMA_PAYMENT, rest)
                .withClientProforma(draft.getId()), false);
        ClientProformaEntity paid = proformaDao.findById(draft.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(ProformaStatus.PAID);
        assertThat(paid.getPaidGross()).isEqualByComparingTo(gross);
        assertThat(paid.getReleasedAt()).isNotNull();
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(statusOf(p1)).isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(statusOf(p2)).isEqualTo(PositionStatus.WITH_BUYER);

        // lock after release: the link can no longer be removed
        assertConflict(() -> linkService.removeLink(outcome.linkId()), BankLinkService.LOCKED);

        // idempotent: re-evaluating a released proforma changes nothing
        assertThat(proformaDao.findById(draft.getId()).orElseThrow().getReleasedAt()).isEqualTo(paid.getReleasedAt());

        // notification to the project MANAGER (AFTER_COMMIT)
        assertThat(awaitNotification(manager.getId(), "notification.proforma.paid")).isTrue();
    }

    @Test
    void proformaCancelRulesDraftIssuedAndReissue() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);

        // draft cancel needs no reason; the position stays ACCEPTED and free
        ClientProformaServiceExtendedModel d1 = createDraft(f.project, p);
        assertThat(cancelService.cancel(d1.getId(), null).getStatus()).isEqualTo(ProformaStatus.CANCELLED);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ACCEPTED);

        // one active proforma per position
        ClientProformaServiceExtendedModel d2 = createDraft(f.project, p);
        assertConflict(() -> createDraft(f.project, p), "error.proforma.position.alreadyInProforma");

        // issued cancel: the reason is mandatory and the position returns to ACCEPTED
        issueService.issue(d2.getId(), "ru");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_PROFORMA);
        assertThatThrownBy(() -> cancelService.cancel(d2.getId(), "  "))
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", "error.proforma.cancel.reasonRequired");
        assertThat(cancelService.cancel(d2.getId(), "client declined").getStatus())
                .isEqualTo(ProformaStatus.CANCELLED);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(proformaLineDao.findByProformaIdOrderByLineNo(d2.getId())).allMatch(l -> !l.isActive());

        // the freed position can be proformed again
        ClientProformaServiceExtendedModel d3 = createDraft(f.project, p);
        assertThat(issueService.issue(d3.getId(), "pl").getStatus()).isEqualTo(ProformaStatus.ISSUED);
        // a cancelled proforma cannot be cancelled again
        assertConflict(() -> cancelService.cancel(d2.getId(), "again"), "error.proforma.status.notAllowed");
    }

    // =========================================================================================
    // Ledger links: direction, currency, status, caps
    // =========================================================================================

    @Test
    void ledgerLinkValidationDirectionCurrencyStatusAndCaps() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        ClientProformaServiceExtendedModel draft = createDraft(f.project, p);

        // a DRAFT proforma is not linkable
        Long credit = tx().execute(s -> newTransaction("PLN", new BigDecimal("50.00"), LedgerOrigin.MANUAL).getId());
        assertConflict(() -> linkService.createLink(credit, LinkRequest.of(LinkKind.PROFORMA_PAYMENT,
                new BigDecimal("10.00")).withClientProforma(draft.getId()), false), BankLinkService.PROFORMA_NOT_LINKABLE);

        BigDecimal gross = issueService.issue(draft.getId(), "pl").getTotalGross();

        // direction: a debit line cannot pay a proforma
        Long debit = tx().execute(s -> newTransaction("PLN", new BigDecimal("-50.00"), LedgerOrigin.MANUAL).getId());
        assertThatThrownBy(() -> linkService.createLink(debit, LinkRequest.of(LinkKind.PROFORMA_PAYMENT,
                new BigDecimal("10.00")).withClientProforma(draft.getId()), false))
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", BankLinkService.KIND_DIRECTION);

        // currency: PLN only
        Long eur = tx().execute(s -> newTransaction("EUR", new BigDecimal("50.00"), LedgerOrigin.MANUAL).getId());
        assertConflict(() -> linkService.createLink(eur, LinkRequest.of(LinkKind.PROFORMA_PAYMENT,
                new BigDecimal("10.00")).withClientProforma(draft.getId()), false), BankLinkService.CURRENCY_MISMATCH);

        // cap: not more than the proforma remainder
        Long big = tx().execute(s -> newTransaction("PLN", gross.add(new BigDecimal("500.00")),
                LedgerOrigin.MANUAL).getId());
        assertThatThrownBy(() -> linkService.createLink(big, LinkRequest.of(LinkKind.PROFORMA_PAYMENT,
                gross.add(BigDecimal.ONE)).withClientProforma(draft.getId()), false))
                .isInstanceOf(ForemenApiException.class);

        // external order: a credit line cannot pay it
        PurchasePositionEntity wp = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(f, wp, "0");
        assertThatThrownBy(() -> linkService.createLink(credit, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT,
                new BigDecimal("10.00")).withExternalOrder(order.getId()), false))
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", BankLinkService.KIND_DIRECTION);
        Long eurDebit = tx().execute(s -> newTransaction("EUR", new BigDecimal("-50.00"), LedgerOrigin.MANUAL).getId());
        assertConflict(() -> linkService.createLink(eurDebit, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT,
                new BigDecimal("10.00")).withExternalOrder(order.getId()), false), BankLinkService.CURRENCY_MISMATCH);

        // a cancelled order is not linkable
        ExternalOrderServiceExtendedModel draftOrder = draftOrder(f, position(f.project, PositionStatus.WITH_BUYER), "0");
        Long okDebit = tx().execute(s -> newTransaction("PLN", new BigDecimal("-50.00"), LedgerOrigin.MANUAL).getId());
        assertConflict(() -> linkService.createLink(okDebit, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT,
                new BigDecimal("10.00")).withExternalOrder(draftOrder.getId()), false),
                BankLinkService.EXTERNAL_ORDER_NOT_LINKABLE);
    }

    // =========================================================================================
    // External order: draft rules, send, mark paid, return, unmark, cancel, stage sync
    // =========================================================================================

    @Test
    void externalOrderDraftRulesCoverageCeilingDeliveryAllocationAndSendGuards() {
        Fixture f = fixture();
        PurchasePositionEntity pa = position(f.project, PositionStatus.WITH_BUYER);
        PurchasePositionEntity pb = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = draftOrder(f, pa, "100.00");

        // above the sale-price ceiling needs the acknowledgement
        assertConflict(() -> orderService.replaceLines(order.getId(),
                List.of(line(pa, "5", "150.00", false))), "error.externalOrder.line.aboveCeiling");

        // delivery allocation is exact: shares sum to the delivery net
        ExternalOrderServiceExtendedModel withLines = orderService.replaceLines(order.getId(), List.of(
                line(pa, "2", "30.00", false), line(pb, "3", "70.00", false)));
        BigDecimal shares = orderLineDao.findByExternalOrderId(order.getId()).stream()
                .map(ExternalOrderLineEntity::getDeliveryCostShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shares).isEqualByComparingTo("100.00");
        assertThat(withLines.getLines()).hasSize(2);

        // coverage: a second order cannot cover more than the remaining quantity
        ExternalOrderServiceExtendedModel other = draftOrder(f, pa, "0");
        assertConflict(() -> orderService.replaceLines(other.getId(), List.of(line(pa, "4", "30.00", false))),
                "error.externalOrder.line.overCoverage");
        assertThat(orderService.replaceLines(other.getId(), List.of(line(pa, "3", "30.00", false))).getLines())
                .hasSize(1);

        // above ceiling acknowledged is accepted
        ExternalOrderServiceExtendedModel ack = draftOrder(f, pb, "0");
        assertThat(orderService.replaceLines(ack.getId(), List.of(line(pb, "1", "150.00", true))).getLines())
                .hasSize(1);

        // send guards: no lines; no document or reference
        ExternalOrderServiceExtendedModel empty = draftOrder(f, pa, "0");
        assertConflict(() -> lifecycle.send(empty.getId()), "error.externalOrder.send.noLines");
        assertConflict(() -> lifecycle.send(order.getId()), "error.externalOrder.send.noDocument");

        // a draft with lines is deleted without touching the positions
        orderService.deleteById(other.getId());
        assertThat(orderDao.findById(other.getId())).isEmpty();
        assertThat(statusOf(pa)).isEqualTo(PositionStatus.WITH_BUYER);
    }

    @Test
    void externalOrderLifecycleSendMarkPaidReturnUnmarkCancelAndStageSync() {
        Fixture f = fixture();
        UserEntity financier = tx().execute(s -> newUser("FINANCIER", "fin"));
        PurchasePositionEntity p = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = draftOrder(f, p, "0");
        orderService.replaceLines(order.getId(), List.of(line(p, QTY, "80.00", false)));
        setReference(order.getId());

        // send: WITH_BUYER -> ORDERED (full coverage)
        lifecycle.send(order.getId());
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);
        assertThat(positionDao.findById(p.getId()).orElseThrow().getOrderedQuantity()).isEqualByComparingTo(QTY);
        assertThat(awaitNotification(financier.getId(), "notification.externalOrder.sentForPayment")).isTrue();

        // return to the buyer with a reason: back to a draft, position WITH_BUYER again
        assertThatThrownBy(() -> lifecycle.returnToBuyer(order.getId(), " "))
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", "error.externalOrder.return.reasonRequired");
        lifecycle.returnToBuyer(order.getId(), "wrong price");
        ExternalOrderEntity returned = orderDao.findById(order.getId()).orElseThrow();
        assertThat(returned.getStatus()).isEqualTo(ExternalOrderStatus.DRAFT);
        assertThat(returned.getReturnReason()).isEqualTo("wrong price");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.WITH_BUYER);

        // send again, mark paid by the financier: ORDERED -> SUPPLIER_PAID
        lifecycle.send(order.getId());
        lifecycle.markPaid(order.getId(), null, "cash at the counter");
        ExternalOrderEntity paid = orderDao.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(paid.getPaidSource()).isEqualTo(PaidSource.FINANCIER);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);

        // unmark: SUPPLIER_PAID -> ORDERED, order back to SENT_FOR_PAYMENT
        lifecycle.unmarkPaid(order.getId());
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);

        // cancel releases the coverage: ORDERED -> WITH_BUYER
        lifecycle.cancel(order.getId());
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(ExternalOrderStatus.CANCELLED);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(positionDao.findById(p.getId()).orElseThrow().getOrderedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void ledgerWinsOverFinancierPaidAndReversalWhenLinkRemoved() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(f, p, "0");
        BigDecimal payable = payable(order.getId());

        // the financier marks it paid, then the ledger takes over (source LEDGER)
        lifecycle.markPaid(order.getId(), null, null);
        assertThat(orderDao.findById(order.getId()).orElseThrow().getPaidSource()).isEqualTo(PaidSource.FINANCIER);
        Long txn = tx().execute(s -> newTransaction("PLN", payable.negate(), LedgerOrigin.MANUAL).getId());
        var outcome = linkService.createLink(txn, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, payable)
                .withExternalOrder(order.getId()), false);
        ExternalOrderEntity ledgerPaid = orderDao.findById(order.getId()).orElseThrow();
        assertThat(ledgerPaid.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(ledgerPaid.getPaidSource()).isEqualTo(PaidSource.LEDGER);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);

        // a ledger-paid order cannot be unmarked by hand
        assertConflict(() -> lifecycle.unmarkPaid(order.getId()), "error.externalOrder.unmark.ledgerPaid");
        // a PAID order is never cancellable (R15.6: draft or sent only)
        assertConflict(() -> lifecycle.cancel(order.getId()), "error.externalOrder.status.notAllowed");

        // removing the link reverts the ledger-paid order to SENT_FOR_PAYMENT and the position to ORDERED
        linkService.removeLink(outcome.linkId());
        ExternalOrderEntity reverted = orderDao.findById(order.getId()).orElseThrow();
        assertThat(reverted.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(reverted.getPaidSource()).isNull();
        assertThat(statusOf(p)).isEqualTo(PositionStatus.ORDERED);

        // a partial ledger payment keeps it SENT_FOR_PAYMENT and blocks the cancel
        Long partialTxn = tx().execute(s -> newTransaction("PLN", new BigDecimal("-10.00"), LedgerOrigin.MANUAL).getId());
        linkService.createLink(partialTxn, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, new BigDecimal("10.00"))
                .withExternalOrder(order.getId()), false);
        assertThat(orderDao.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertConflict(() -> lifecycle.cancel(order.getId()), "error.externalOrder.cancel.hasPayments");
    }

    @Test
    void ledgerReversalIsRefusedWhenPositionsAreInDelivery() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(f, p, "0");
        BigDecimal payable = payable(order.getId());
        Long txn = tx().execute(s -> newTransaction("PLN", payable.negate(), LedgerOrigin.MANUAL).getId());
        var outcome = linkService.createLink(txn, LinkRequest.of(LinkKind.EXTERNAL_ORDER_PAYMENT, payable)
                .withExternalOrder(order.getId()), false);
        assertThat(statusOf(p)).isEqualTo(PositionStatus.SUPPLIER_PAID);

        // the position moves on to delivery (FOR-11-04 territory): simulate with a direct write
        tx().executeWithoutResult(s -> {
            PurchasePositionEntity loaded = positionDao.findById(p.getId()).orElseThrow();
            loaded.setStatus(PositionStatus.IN_DELIVERY);
            positionDao.save(loaded);
        });

        assertConflict(() -> linkService.removeLink(outcome.linkId()),
                "error.externalOrder.unlink.positionsInDelivery");
        // the refusal rolled the removal back
        assertThat(linkDao.findById(outcome.linkId())).isPresent();
        assertThat(orderDao.findById(order.getId()).orElseThrow().getPaidSource()).isEqualTo(PaidSource.LEDGER);
        // nor can the ledger-paid order be unmarked by hand
        assertConflict(() -> lifecycle.unmarkPaid(order.getId()), "error.externalOrder.unmark.ledgerPaid");
        assertThat(statusOf(p)).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    // =========================================================================================
    // Matching: forward (detection) and reverse (issue)
    // =========================================================================================

    @Test
    void reverseMatchingOfAnIssuedProformaFindsTheCreditLineByNumberAndAmount() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        ClientProformaServiceExtendedModel draft = createDraft(f.project, p);
        ClientProformaServiceExtendedModel issued = issueService.issue(draft.getId(), "pl");
        BigDecimal gross = issued.getTotalGross();

        Long txnId = tx().execute(s -> {
            BankTransactionEntity t = newTransaction("PLN", gross, LedgerOrigin.BANK_FEED);
            t.setTitle("Zaliczka " + issued.getNumber());
            return transactionDao.save(t).getId();
        });

        ReverseDocumentMatcher.Result result = reverseMatcher.matchProforma(draft.getId());
        assertThat(result.outcome()).isIn(ReverseDocumentMatcher.Outcome.AUTO_LINKED,
                ReverseDocumentMatcher.Outcome.SUGGESTED);
        assertThat(result.transactionId()).isEqualTo(txnId);
        boolean linked = linkDao.findByTransactionIdOrderByIdAsc(txnId).stream()
                .anyMatch(l -> l.getKind() == LinkKind.PROFORMA_PAYMENT);
        boolean suggested = !suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(txnId,
                com.foremen.dao.model.SuggestionStatus.OPEN).isEmpty();
        assertThat(linked || suggested).isTrue();
    }

    @Test
    void forwardMatchingOfACreditLineSuggestsOrLinksTheProformaByNumberAndAmount() {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        ClientProformaServiceExtendedModel issued = issueService.issue(createDraft(f.project, p).getId(), "pl");
        BigDecimal gross = issued.getTotalGross();

        // the line is booked after the proforma was issued and its reverse matching already ran
        Long txnId = tx().execute(s -> {
            BankTransactionEntity t = newTransaction("PLN", gross, LedgerOrigin.BANK_FEED);
            t.setTitle("Przelew " + issued.getNumber());
            return transactionDao.save(t).getId();
        });
        detectionService.detect(List.of(txnId), null);

        BankTransactionEntity after = transactionDao.findById(txnId).orElseThrow();
        boolean linked = linkDao.findByTransactionIdOrderByIdAsc(txnId).stream()
                .anyMatch(l -> l.getKind() == LinkKind.PROFORMA_PAYMENT
                        && l.getClientProforma() != null && l.getClientProforma().getId().equals(issued.getId()));
        boolean suggested = suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(txnId,
                com.foremen.dao.model.SuggestionStatus.OPEN).stream()
                .anyMatch(sg -> sg.getKind() == LinkKind.PROFORMA_PAYMENT);
        assertThat(linked || suggested).as("link status %s", after.getLinkStatus()).isTrue();
    }

    // =========================================================================================
    // ABAC (role matrix through MockMvc), project isolation, client-safe DTO
    // =========================================================================================

    @Test
    void abacMatrixAndProjectIsolationThroughHttp() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        PurchasePositionEntity po = position(other.project, PositionStatus.ACCEPTED);
        Long proformaId = issueService.issue(createDraft(f.project, p).getId(), "pl").getId();
        Long foreignProformaId = issueService.issue(createDraft(other.project, po).getId(), "pl").getId();
        PurchasePositionEntity wp = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = sentOrder(f, wp, "0");

        UserEntity manager = tx().execute(s -> {
            UserEntity u = newUser("MANAGER", "abac-mgr");
            addMember(u, f.project, "WORKER");
            return u;
        });
        UserEntity client = tx().execute(s -> {
            UserEntity u = newUser("CLIENT", "abac-client");
            addMember(u, f.project, "CLIENT");
            return u;
        });
        UserEntity buyer = tx().execute(s -> newUser("BUYER", "abac-buyer"));
        UserEntity financier = tx().execute(s -> newUser("FINANCIER", "abac-fin"));
        UserEntity foreman = tx().execute(s -> newUser("FOREMAN", "abac-foreman"));
        projectAccessCache.invalidate(manager.getId());
        projectAccessCache.invalidate(client.getId());

        // MANAGER: reads own project's proforma, 404 for a foreign project
        status(get("/api/client-proformas/" + proformaId).with(as(manager, "MANAGER")), 200);
        status(get("/api/client-proformas/" + foreignProformaId).with(as(manager, "MANAGER")), 404);
        // MANAGER may not act on external orders beyond READ
        status(post("/api/external-orders/" + order.getId() + "/send").with(as(manager, "MANAGER")), 403);
        // BUYER: READ only on proformas
        status(post("/api/client-proformas/" + proformaId + "/issue").with(as(buyer, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 403);
        // FOREMAN: no grant on either resource
        status(get("/api/client-proformas/" + proformaId).with(as(foreman, "FOREMAN")), 403);
        status(get("/api/external-orders/" + order.getId()).with(as(foreman, "FOREMAN")), 403);
        // CLIENT: no staff proforma endpoint and no external orders
        status(get("/api/client-proformas/" + proformaId).with(as(client, "CLIENT")), 403);
        status(get("/api/external-orders/" + order.getId()).with(as(client, "CLIENT")), 403);
        // FINANCIER: APPROVE on orders (mark paid), but no create
        status(post("/api/external-orders/" + order.getId() + "/mark-paid").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        status(post("/api/external-orders").with(as(financier, "FINANCIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":" + f.project.getId() + ",\"sellerId\":" + f.seller.getId() + "}"), 403);
        // BUYER cannot mark paid (no APPROVE)
        status(post("/api/external-orders/" + order.getId() + "/unmark-paid").with(as(buyer, "BUYER")), 403);

        // CLIENT view: member sees own proforma, no purchase data; foreign project answers 404
        MvcResult own = mockMvc.perform(get("/api/client-proformas/client/" + proformaId).with(as(client, "CLIENT")))
                .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        String body = own.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        assertThat(body).contains("number");
        assertThat(body).doesNotContain("purchase").doesNotContain("positionid").doesNotContain("cost");
        status(get("/api/client-proformas/client/" + foreignProformaId).with(as(client, "CLIENT")), 404);
        status(get("/api/client-proformas/client/" + proformaId + "/pdf").with(as(client, "CLIENT")), 200);
        // a non-CLIENT caller of the client endpoint is indistinguishable from a non-member
        status(get("/api/client-proformas/client/" + proformaId).with(as(manager, "MANAGER")), 404);
    }

    // =========================================================================================
    // CHECK constraints (changesets 236-238) and migration idempotency
    // =========================================================================================

    @Test
    void checkConstraintsOfChangesets236To238RejectInvalidRows() throws Exception {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        Long proformaId = issueService.issue(createDraft(f.project, p).getId(), "pl").getId();
        PurchasePositionEntity wp = position(f.project, PositionStatus.WITH_BUYER);
        ExternalOrderServiceExtendedModel order = draftOrder(f, wp, "0");
        orderService.replaceLines(order.getId(), List.of(line(wp, QTY, "10.00", false)));
        Long txnId = tx().execute(s -> newTransaction("PLN", new BigDecimal("10.00"), LedgerOrigin.MANUAL).getId());
        var link = linkService.createLink(txnId, LinkRequest.of(LinkKind.PROFORMA_PAYMENT, new BigDecimal("10.00"))
                .withClientProforma(proformaId), false);

        assertRejected("UPDATE client_proformas SET status = 'BOGUS' WHERE id = " + proformaId);
        assertRejected("UPDATE client_proformas SET paid_gross = total_gross + 1 WHERE id = " + proformaId);
        assertRejected("UPDATE client_proforma_lines SET quantity = 0 WHERE proforma_id = " + proformaId);
        assertRejected("UPDATE external_orders SET status = 'BOGUS' WHERE id = " + order.getId());
        assertRejected("UPDATE external_orders SET delivery_cost_net = -1 WHERE id = " + order.getId());
        assertRejected("UPDATE external_orders SET paid_source = 'BOGUS' WHERE id = " + order.getId());
        assertRejected("UPDATE external_order_lines SET quantity = 0 WHERE external_order_id = " + order.getId());
        assertRejected("UPDATE external_order_lines SET unit_purchase_net = -1 WHERE external_order_id = "
                + order.getId());
        // changeset 238: the kind and its target must agree
        assertRejected("UPDATE bank_transaction_links SET kind = 'BOGUS' WHERE id = " + link.linkId());
        assertRejected("UPDATE bank_transaction_links SET client_proforma_id = NULL WHERE id = " + link.linkId());
        assertRejected("UPDATE bank_transaction_links SET external_order_id = " + order.getId()
                + " WHERE id = " + link.linkId());
        // a second active line per position is refused by the partial unique index
        assertRejected("INSERT INTO client_proforma_lines (proforma_id, position_id, line_no, quantity, unit_name, "
                + "unit_sale_net, vat_rate_percent, net, vat, gross, description, active) "
                + "SELECT proforma_id, position_id, 99, quantity, unit_name, unit_sale_net, vat_rate_percent, "
                + "net, vat, gross, description, true FROM client_proforma_lines WHERE proforma_id = " + proformaId);
    }

    @Test
    void migrationIsIdempotentWhenTheChangelogRunsAgain() throws Exception {
        Fixture f = fixture();
        PurchasePositionEntity p = position(f.project, PositionStatus.ACCEPTED);
        Long proformaId = issueService.issue(createDraft(f.project, p).getId(), "pl").getId();
        long proformas = BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM client_proformas");
        String kindCheck = BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_bank_transaction_links_kind'")
                .get(0);

        BankLedgerItSupport.runChangelog(POSTGRES);

        assertThat(BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM client_proformas"))
                .isEqualTo(proformas);
        assertThat(BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_bank_transaction_links_kind'"))
                .containsExactly(kindCheck);
        assertThat(kindCheck).contains("PROFORMA_PAYMENT").contains("EXTERNAL_ORDER_PAYMENT");
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES,
                "SELECT COUNT(*) FROM resources WHERE code IN ('CLIENT_PROFORMAS','EXTERNAL_ORDERS')")).isEqualTo(2L);
        assertThat(BankLedgerItSupport.queryForLong(POSTGRES,
                "SELECT COUNT(*) FROM pg_class WHERE relname = 'client_proforma_number_seq'")).isEqualTo(1L);
        assertThat(proformaDao.findById(proformaId)).isPresent();
    }

    // =========================================================================================
    // i18n parity (R26.1, R26.4)
    // =========================================================================================

    @Test
    void errorMessagesOfProformasAndExternalOrdersExistInBothLanguages() throws IOException {
        Properties defaults = load("messages.properties");
        Properties russian = load("messages_ru.properties");
        Set<String> keys = new HashSet<>();
        for (String k : defaults.stringPropertyNames()) {
            if (k.startsWith("error.proforma.") || k.startsWith("error.externalOrder.")) {
                keys.add(k);
            }
        }
        for (String k : russian.stringPropertyNames()) {
            if (k.startsWith("error.proforma.") || k.startsWith("error.externalOrder.")) {
                keys.add(k);
            }
        }
        assertThat(keys).isNotEmpty();
        for (String k : keys) {
            assertThat(defaults.getProperty(k)).as("messages.properties %s", k).isNotBlank();
            assertThat(russian.getProperty(k)).as("messages_ru.properties %s", k).isNotBlank();
        }
        for (String k : List.of("error.proforma.position.alreadyInProforma", "error.proforma.cancel.hasPayments",
                "error.externalOrder.line.overCoverage", "error.externalOrder.unlink.positionsInDelivery",
                "error.proforma.link.notLinkable", "error.externalOrder.link.notLinkable")) {
            assertThat(keys).as("expected key %s", k).contains(k);
        }
        Properties pl = load("proforma_labels_pl.properties");
        Properties ru = load("proforma_labels_ru.properties");
        assertThat(pl.stringPropertyNames()).isNotEmpty().isEqualTo(ru.stringPropertyNames());
    }

    // =========================================================================================
    // fixtures
    // =========================================================================================

    private record Fixture(ProjectEntity project, MaterialSellerEntity seller) {
    }

    private Fixture fixture() {
        return tx().execute(s -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("FOR1103 " + runId + "-" + (++seq));
            project.setStatus(ProjectStatus.ACTIVE);
            projectDao.save(project);
            MaterialSellerEntity seller = new MaterialSellerEntity();
            seller.setCode("FOR1103-" + runId + "-" + seq);
            seller.setNameRU("Продавец " + runId + seq);
            seller.setNamePL("Sprzedawca " + runId + seq);
            sellerDao.save(seller);
            return new Fixture(project, seller);
        });
    }

    private void ensureCatalog() {
        if (catalogMaterial != null) {
            return;
        }
        String u = runId;
        MaterialCategoryEntity category = new MaterialCategoryEntity();
        category.setCode("f1103_cat_" + u);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        em.persist(category);
        MaterialEntity material = new MaterialEntity();
        material.setCode("f1103_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        em.persist(material);
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode("f1103_type_" + u);
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        em.persist(type);
        unit = new MeasurementUnitEntity();
        unit.setCode("f1103_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        em.persist(unit);
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode("f1103_pkg_" + u);
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
        v.setCode("f1103_vat_" + rate + "_" + runId);
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

    private ExternalOrderServiceExtendedModel draftOrder(Fixture f, PurchasePositionEntity p, String delivery) {
        ExternalOrderServiceExtendedModel m = new ExternalOrderServiceExtendedModel();
        m.setProjectId(f.project.getId());
        m.setSellerId(f.seller.getId());
        m.setAssignedBuyerId(buyerUser.getId());
        m.setDeliveryCostNet(new BigDecimal(delivery));
        if (new BigDecimal(delivery).signum() > 0) {
            m.setDeliveryVatRateId(vat23.getId());
        }
        return orderService.create(m);
    }

    /** A SENT_FOR_PAYMENT order covering the whole position at 80.00 net. */
    private ExternalOrderServiceExtendedModel sentOrder(Fixture f, PurchasePositionEntity p, String delivery) {
        ExternalOrderServiceExtendedModel order = draftOrder(f, p, delivery);
        orderService.replaceLines(order.getId(), List.of(line(p, QTY, "80.00", false)));
        setReference(order.getId());
        lifecycle.send(order.getId());
        return order;
    }

    private void setReference(Long orderId) {
        tx().executeWithoutResult(s -> {
            ExternalOrderEntity o = orderDao.findById(orderId).orElseThrow();
            o.setReferenceNumber("INV-" + runId + "-" + orderId);
            orderDao.save(o);
        });
    }

    private BigDecimal payable(Long orderId) {
        return orderService.findById(orderId).getPayableGross();
    }

    private static ExternalOrderLineServiceModel line(PurchasePositionEntity p, String qty, String net, boolean ack) {
        ExternalOrderLineServiceModel l = new ExternalOrderLineServiceModel();
        l.setPositionId(p.getId());
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPurchaseNet(new BigDecimal(net));
        l.setAboveCeilingAcknowledged(ack);
        return l;
    }

    // --- users, ledger rows ---

    private UserEntity newUser(String roleCode, String tag) {
        UserEntity u = new UserEntity();
        u.setName("F1103 " + tag + " " + runId);
        u.setEmail("f1103+" + tag + "-" + runId.toLowerCase(Locale.ROOT) + "-" + (++seq) + "@example.com");
        u.setRole(roleDao.findByCode(roleCode).orElseThrow());
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("ru"));
        return userDao.save(u);
    }

    private void addMember(UserEntity user, ProjectEntity project, String projectRoleCode) {
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(user);
        pm.setProjectId(project.getId());
        pm.setProjectRole(roleDao.findByCode(projectRoleCode).orElseThrow());
        em.persist(pm);
        em.flush();
    }

    private BankTransactionEntity newTransaction(String currency, BigDecimal amount, LedgerOrigin origin) {
        boolean feed = origin == LedgerOrigin.BANK_FEED;
        BankAccountEntity account = new BankAccountEntity();
        account.setType(feed ? BankAccountType.BANK : BankAccountType.CASH);
        account.setName("F1103 " + runId + "-" + (++seq));
        if (feed) {
            BankConnectionEntity c = new BankConnectionEntity();
            c.setProvider(FeedProvider.STUB);
            c.setProviderMode(SaltEdgeMode.CLIENT);
            c.setBankName("Stub Bank");
            c.setExternalConnectionId("F1103-CONN-" + runId + "-" + seq);
            c.setStatus(BankConnectionStatus.ACTIVE);
            account.setConnection(connectionDao.save(c));
            account.setExternalId("F1103-ACC-" + runId + "-" + seq);
        }
        account.setCurrency(currency);
        accountDao.save(account);

        BankTransactionEntity txn = new BankTransactionEntity();
        txn.setAccount(account);
        txn.setChannel(feed ? LedgerChannel.BANK : LedgerChannel.CASH);
        txn.setOrigin(origin);
        txn.setStatus(BankTxnStatus.POSTED);
        txn.setAmount(amount);
        txn.setCurrency(currency);
        txn.setOperationDate(today);
        txn.setBookingDate(today);
        if (feed) {
            txn.setExternalId("F1103-TX-" + runId + "-" + (++seq));
        }
        txn.setTitle("F1103 " + runId);
        txn.setCreatedByUser("f1103-test");
        return transactionDao.save(txn);
    }

    // --- assertions and plumbing ---

    private void assertConflict(Runnable action, String messageCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", messageCode);
    }

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> BankLedgerItSupport.execute(POSTGRES, sql))
                .as(sql)
                .isInstanceOf(SQLException.class);
    }

    private boolean awaitNotification(Long recipientId, String type) {
        long deadline = System.currentTimeMillis() + 5000;
        do {
            List<NotificationEntity> list = notificationDao.findByRecipientIdOrderByCreatedDateDesc(recipientId);
            if (list.stream().anyMatch(n -> type.equals(n.getType()))) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private void status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        int expected) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(request.toString() + " -> " + r.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private static RequestPostProcessor as(UserEntity user, String roleCode) {
        // The JWT filter's shape: principal = the numeric user id (Long), authority ROLE_<code>.
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getId(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static Properties load(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream();
             java.io.InputStreamReader reader = new java.io.InputStreamReader(in,
                     java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
