package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.bank.link.ExternalOrderLedgerEffects;
import com.foremen.service.bank.link.LinkEffects;
import com.foremen.service.procurement.externalorder.ExternalOrderPaymentService;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;

/**
 * FOR-11-03 task 5.3 (R16.1 - R16.6): the Spring wiring of the ledger-driven external-order payment.
 * {@link LinkEffects} gets the {@link ExternalOrderLedgerEffects} port by direct injection, and a link
 * change applied through it reaches {@link ExternalOrderPaymentService#reevaluate(Long)} and marks a
 * {@code SENT_FOR_PAYMENT} order {@code PAID} with source {@code LEDGER}.
 *
 * <p>Repeatability: a fresh container per run; sellers, projects, users and e-mails carry a per-run id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@Import(InMemoryImageStorageConfig.class)
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-11-03, task 5.3: ledger-driven external order payment")
class ExternalOrderLedgerWiringIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (ExternalOrderLedgerWiringIntegrationTest.class) {
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

    @Autowired private LinkEffects linkEffects;
    @Autowired private ExternalOrderLedgerEffects port;
    @Autowired private BankAccountDao accountDao;
    @Autowired private BankTransactionDao transactionDao;
    @Autowired private BankTransactionLinkDao linkDao;
    @Autowired private ExternalOrderDao orderDao;
    @Autowired private ExternalOrderDocumentDao orderDocumentDao;
    @Autowired private ProcurementDocumentDao documentDao;
    @Autowired private MaterialSellerDao sellerDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int seq;

    @Test
    void linkEffectsIsWiredToTheExternalOrderPaymentService() {
        assertThat(port).isInstanceOf(ExternalOrderPaymentService.class);
        assertThat(ReflectionTestUtils.getField(linkEffects, "externalOrderEffects")).isSameAs(port);
    }

    @Test
    void procurementLinkOnDocumentOfSentOrderMarksItPaidByLedger() {
        Long orderId = tx().execute(s -> newSentOrder().getId());
        Long docId = tx().execute(s -> {
            ProcurementDocumentEntity doc = newDocument(new BigDecimal("123.00"));
            ExternalOrderDocumentEntity link = new ExternalOrderDocumentEntity();
            link.setExternalOrder(orderDao.findById(orderId).orElseThrow());
            link.setProcurementDocument(doc);
            orderDocumentDao.save(link);
            return doc.getId();
        });

        tx().executeWithoutResult(s -> {
            BankTransactionEntity txn = newTransaction(new BigDecimal("123.00"));
            BankTransactionLinkEntity link = newLink(txn, LinkKind.PROCUREMENT, new BigDecimal("123.00"));
            link.setProcurementDocument(documentDao.findById(docId).orElseThrow());
            linkDao.save(link);
            linkEffects.apply(txn, link);
        });

        ExternalOrderEntity order = orderDao.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.LEDGER);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(order.getPaidBy()).isNull();
    }

    @Test
    void externalOrderPaymentLinkCoveringPayableGrossMarksOrderPaidByLedger() {
        Long orderId = tx().execute(s -> newSentOrder().getId());

        tx().executeWithoutResult(s -> {
            BankTransactionEntity txn = newTransaction(new BigDecimal("100.00"));
            BankTransactionLinkEntity link = newLink(txn, LinkKind.EXTERNAL_ORDER_PAYMENT, new BigDecimal("100.00"));
            link.setExternalOrder(orderDao.findById(orderId).orElseThrow());
            linkDao.save(link);
            linkEffects.apply(txn, link);
        });

        ExternalOrderEntity order = orderDao.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.LEDGER);
    }

    // --- fixtures ---

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** A SENT_FOR_PAYMENT order without lines whose payable gross is its 100.00 delivery cost. */
    private ExternalOrderEntity newSentOrder() {
        ProjectEntity project = new ProjectEntity();
        project.setName("EO ledger wiring " + runId + "-" + (++seq));
        project.setStatus(ProjectStatus.ACTIVE);
        projectDao.save(project);

        MaterialSellerEntity seller = new MaterialSellerEntity();
        seller.setCode("EO-LEDGER-" + runId + "-" + seq);
        seller.setNameRU("Продавец " + runId);
        seller.setNamePL("Sprzedawca " + runId);
        sellerDao.save(seller);

        ExternalOrderEntity order = new ExternalOrderEntity();
        order.setProject(project);
        order.setSeller(seller);
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        order.setDeliveryCostNet(new BigDecimal("100.00"));
        order.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
        return orderDao.save(order);
    }

    private ProcurementDocumentEntity newDocument(BigDecimal total) {
        UserEntity uploader = new UserEntity();
        uploader.setName("EO ledger uploader " + runId);
        uploader.setEmail("eo-ledger+" + runId.toLowerCase(Locale.ROOT) + "-" + (++seq) + "@example.com");
        uploader.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        uploader.setActive(true);
        userDao.save(uploader);

        ProcurementDocumentEntity doc = new ProcurementDocumentEntity();
        doc.setUploadedBy(uploader);
        doc.setRawObjectKey("raw/" + runId + "/" + seq);
        doc.setSourceMimeType("application/pdf");
        doc.setStatus(ProcessingStatus.PARSED);
        doc.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
        doc.setDocumentTotal(total);
        return documentDao.save(doc);
    }

    private BankTransactionEntity newTransaction(BigDecimal amount) {
        BankAccountEntity account = new BankAccountEntity();
        account.setType(BankAccountType.CASH);
        account.setName("Kasa EO " + runId + "-" + (++seq));
        account.setCurrency("PLN");
        accountDao.save(account);

        BankTransactionEntity txn = new BankTransactionEntity();
        txn.setAccount(account);
        txn.setChannel(LedgerChannel.CASH);
        txn.setOrigin(LedgerOrigin.MANUAL);
        txn.setStatus(BankTxnStatus.POSTED);
        txn.setAmount(amount.negate());
        txn.setCurrency("PLN");
        txn.setOperationDate(today);
        txn.setBookingDate(today);
        txn.setTitle("EO ledger " + runId);
        txn.setCreatedByUser("eo-ledger-test");
        return transactionDao.save(txn);
    }

    private BankTransactionLinkEntity newLink(BankTransactionEntity txn, LinkKind kind, BigDecimal amount) {
        BankTransactionLinkEntity link = new BankTransactionLinkEntity();
        link.setTransaction(txn);
        link.setKind(kind);
        link.setAmount(amount);
        link.setSource(DetectionSource.MANUAL);
        return link;
    }
}
