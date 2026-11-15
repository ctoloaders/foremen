package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchaseRequestDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ExternalOrderDocumentServiceModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.mapper.ExternalOrderServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/** FOR-11-03 task 4.2 (R12, R13.1, R14): draft rules of {@link ExternalOrderService}. */
class ExternalOrderServiceTest {

    private static final Long ORDER_ID = 5L;
    private static final Long PROJECT_ID = 3L;

    private ExternalOrderDao dao;
    private ExternalOrderLineDao lineDao;
    private ExternalOrderDocumentDao documentDao;
    private PurchasePositionDao positionDao;
    private ProcurementDocumentDao procurementDocumentDao;
    private VatRateDao vatRateDao;
    private ApplicationEventPublisher publisher;
    private ExternalOrderService service;
    private ExternalOrderEntity order;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        dao = mock(ExternalOrderDao.class);
        lineDao = mock(ExternalOrderLineDao.class);
        documentDao = mock(ExternalOrderDocumentDao.class);
        positionDao = mock(PurchasePositionDao.class);
        procurementDocumentDao = mock(ProcurementDocumentDao.class);
        vatRateDao = mock(VatRateDao.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ExternalOrderService(dao, lineDao, documentDao, mock(ExternalOrderServiceMapper.class),
                mock(AuditLogDao.class), mock(EntityManager.class), mock(ProcurementProjectAccess.class),
                mock(ProjectDao.class), mock(MaterialSellerDao.class), mock(PurchaseRequestDao.class),
                positionDao, procurementDocumentDao, vatRateDao, mock(UserDao.class), publisher);

        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        order = new ExternalOrderEntity();
        order.setId(ORDER_ID);
        order.setProject(project);
        order.setStatus(ExternalOrderStatus.DRAFT);
        order.setDeliveryCostNet(new BigDecimal("10.00"));
        when(dao.findById(ORDER_ID)).thenAnswer(i -> Optional.of(order));
        when(lineDao.findByExternalOrderId(ORDER_ID)).thenReturn(List.of());
        when(documentDao.findByExternalOrderId(ORDER_ID)).thenReturn(List.of());
        when(vatRateDao.findAll()).thenReturn(List.of());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private PurchasePositionEntity position(long id, String qty, String salePrice, PositionStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project);
        p.setQuantity(new BigDecimal(qty));
        p.setSalePrice(new BigDecimal(salePrice));
        p.setStatus(status);
        p.setSourcingPath(SourcingPath.AGREED);
        when(positionDao.findAllByIdIn(anyCollection())).thenAnswer(i -> {
            List<PurchasePositionEntity> all = new ArrayList<>();
            all.add(p);
            return all;
        });
        return p;
    }

    private static ExternalOrderLineServiceModel input(long positionId, String qty, String price, boolean ack) {
        ExternalOrderLineServiceModel m = new ExternalOrderLineServiceModel();
        m.setPositionId(positionId);
        m.setQuantity(new BigDecimal(qty));
        m.setUnitPurchaseNet(new BigDecimal(price));
        m.setAboveCeilingAcknowledged(ack);
        return m;
    }

    private static void assertConflict(Runnable call, String key) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(key);
                });
    }

    @Test
    void replaceLinesRejectsNonDraft() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertConflict(() -> service.replaceLines(ORDER_ID, List.of()), ExternalOrderService.NOT_DRAFT);
    }

    @Test
    void replaceLinesRejectsPositionNotWithBuyer() {
        position(1L, "10", "50.00", PositionStatus.ORDERED);
        assertConflict(() -> service.replaceLines(ORDER_ID, List.of(input(1L, "1", "40.00", false))),
                ExternalOrderService.POSITION_NOT_PURCHASABLE);
    }

    @Test
    void replaceLinesRejectsClientOwnPath() {
        PurchasePositionEntity p = position(1L, "10", "50.00", PositionStatus.WITH_BUYER);
        p.setSourcingPath(SourcingPath.CLIENT_OWN);
        assertConflict(() -> service.replaceLines(ORDER_ID, List.of(input(1L, "1", "40.00", false))),
                ExternalOrderService.POSITION_NOT_PURCHASABLE);
    }

    @Test
    void replaceLinesRejectsOverCoverageByOtherOrders() {
        position(1L, "10", "50.00", PositionStatus.WITH_BUYER);
        when(lineDao.sumQuantityByPositionExcluding(anyCollection(), eq(ORDER_ID)))
                .thenReturn(List.<Object[]>of(new Object[] {1L, new BigDecimal("8.000")}));
        assertConflict(() -> service.replaceLines(ORDER_ID, List.of(input(1L, "3", "40.00", false))),
                ExternalOrderCoverageValidator.OVER_COVERAGE);
    }

    @Test
    void replaceLinesRequiresAcknowledgeAboveCeiling() {
        position(1L, "10", "50.00", PositionStatus.WITH_BUYER);
        assertConflict(() -> service.replaceLines(ORDER_ID, List.of(input(1L, "1", "50.01", false))),
                ExternalOrderService.ABOVE_CEILING);
        // price equal to the ceiling needs no acknowledgement
        service.replaceLines(ORDER_ID, List.of(input(1L, "1", "50.00", false)));
    }

    @Test
    void replaceLinesAllocatesDeliveryCostExactlyAndProportionally() {
        PurchasePositionEntity p1 = position(1L, "10", "100.00", PositionStatus.WITH_BUYER);
        PurchasePositionEntity p2 = position(2L, "10", "100.00", PositionStatus.WITH_BUYER);
        when(positionDao.findAllByIdIn(anyCollection())).thenReturn(List.of(p1, p2));

        service.replaceLines(ORDER_ID, List.of(input(1L, "1", "10.00", false), input(2L, "2", "10.00", false)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ExternalOrderLineEntity>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(lineDao).saveAll(saved.capture());
        List<ExternalOrderLineEntity> lines = new ArrayList<>();
        saved.getValue().forEach(lines::add);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getDeliveryCostShare()).isEqualByComparingTo("3.33");
        assertThat(lines.get(1).getDeliveryCostShare()).isEqualByComparingTo("6.67");
        assertThat(lines.stream().map(ExternalOrderLineEntity::getDeliveryCostShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10.00");
    }

    @Test
    void replaceLinesDefaultsPurchaseVatToTheDictionary23Rate() {
        VatRateEntity vat23 = new VatRateEntity();
        vat23.setId(9L);
        vat23.setRate(new BigDecimal("23.00"));
        when(vatRateDao.findAll()).thenReturn(List.of(vat23));
        position(1L, "10", "100.00", PositionStatus.WITH_BUYER);

        service.replaceLines(ORDER_ID, List.of(input(1L, "1", "10.00", false)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ExternalOrderLineEntity>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(lineDao).saveAll(saved.capture());
        assertThat(saved.getValue().iterator().next().getPurchaseVatRate()).isSameAs(vat23);
    }

    @Test
    void replaceDocumentsRejectsPaidOrder() {
        order.setStatus(ExternalOrderStatus.PAID);
        assertConflict(() -> service.replaceDocuments(ORDER_ID, List.of()), ExternalOrderService.DOCUMENTS_LOCKED);
    }

    @Test
    void replaceDocumentsRejectsOverAttributionAndAcceptsWithinTotal() {
        ProcurementDocumentEntity doc = new ProcurementDocumentEntity();
        doc.setId(70L);
        doc.setDocumentTotal(new BigDecimal("100.00"));
        when(procurementDocumentDao.findById(70L)).thenReturn(Optional.of(doc));

        ExternalOrderEntity other = new ExternalOrderEntity();
        other.setId(99L);
        other.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        ExternalOrderDocumentEntity attached = new ExternalOrderDocumentEntity();
        attached.setExternalOrder(other);
        attached.setProcurementDocument(doc);
        attached.setAmount(new BigDecimal("60.00"));
        when(documentDao.findByProcurementDocumentId(70L)).thenReturn(List.of(attached));

        ExternalOrderDocumentServiceModel tooMuch = new ExternalOrderDocumentServiceModel();
        tooMuch.setProcurementDocumentId(70L);
        tooMuch.setAmount(new BigDecimal("40.01"));
        assertConflict(() -> service.replaceDocuments(ORDER_ID, List.of(tooMuch)),
                ExternalOrderService.DOCUMENTS_OVER_ATTRIBUTED);

        ExternalOrderDocumentServiceModel fits = new ExternalOrderDocumentServiceModel();
        fits.setProcurementDocumentId(70L);
        fits.setAmount(new BigDecimal("40.00"));
        service.replaceDocuments(ORDER_ID, List.of(fits));
        verify(publisher).publishEvent(new ExternalOrderDocumentsChangedEvent(ORDER_ID));
    }

    @Test
    void replaceDocumentsRejectsDuplicateDocument() {
        ExternalOrderDocumentServiceModel a = new ExternalOrderDocumentServiceModel();
        a.setProcurementDocumentId(70L);
        assertThatThrownBy(() -> service.replaceDocuments(ORDER_ID, List.of(a, a)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(ExternalOrderService.DOCUMENTS_INVALID));
    }

    @Test
    void sellerMismatchIsFlaggedOnlyWhenBothNipsPresentAndDifferent() {
        assertThat(ExternalOrderServiceMapper.nipsDiffer("PL 123-456-78-90", "1234567890")).isFalse();
        assertThat(ExternalOrderServiceMapper.nipsDiffer("1234567891", "1234567890")).isTrue();
        assertThat(ExternalOrderServiceMapper.nipsDiffer(null, "1234567890")).isFalse();
        assertThat(ExternalOrderServiceMapper.nipsDiffer("1234567891", " ")).isFalse();
    }
}
