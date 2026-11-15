package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-03 task 5.2 (R8.1 - R8.5, R9.1 - R9.6). */
class ProformaPaymentServiceTest {

    private final ClientProformaDao dao = mock(ClientProformaDao.class);
    private final ClientProformaLineDao lineDao = mock(ClientProformaLineDao.class);
    private final BankTransactionLinkDao linkDao = mock(BankTransactionLinkDao.class);
    private final PositionLifecycleService lifecycle = mock(PositionLifecycleService.class);
    private final ClientProformaService proformaService = mock(ClientProformaService.class);
    private final EntityManager em = mock(EntityManager.class);
    private final List<Object> events = new ArrayList<>();
    private final ProformaPaymentService service = new ProformaPaymentService(dao, linkDao,
            new ProformaReleaseService(lineDao, lifecycle, events::add), proformaService, em);

    private ClientProformaEntity proforma;

    @BeforeEach
    void setUp() {
        ProjectEntity project = new ProjectEntity();
        project.setId(9L);
        proforma = new ClientProformaEntity();
        proforma.setId(5L);
        proforma.setProject(project);
        proforma.setStatus(ProformaStatus.ISSUED);
        proforma.setTotalGross(new BigDecimal("123.00"));
        when(em.find(ClientProformaEntity.class, 5L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(proforma);
        when(lineDao.findByProformaIdOrderByLineNo(5L)).thenReturn(List.of(
                line(position(1L, PositionStatus.IN_PROFORMA)),
                line(position(2L, PositionStatus.ACCEPTED))));
    }

    @Test
    void partialPaymentMovesStatusButNoPosition() {
        when(linkDao.sumAmountByClientProformaId(5L)).thenReturn(new BigDecimal("50.00"));

        service.onLinkChanged(5L);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.PARTIALLY_PAID);
        assertThat(proforma.getPaidGross()).isEqualByComparingTo("50.00");
        assertThat(proforma.getReleasedAt()).isNull();
        verify(lifecycle, never()).transition(any(), any(), any());
        assertThat(events).isEmpty();
    }

    @Test
    void fullPaymentReleasesInOrderSkipsForeignPositionAndPublishesOnce() {
        when(linkDao.sumAmountByClientProformaId(5L)).thenReturn(new BigDecimal("123.00"));

        service.onLinkChanged(5L);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.PAID);
        assertThat(proforma.getPaidAt()).isNotNull();
        assertThat(proforma.getReleasedAt()).isNotNull();
        verify(lifecycle).transition(1L, PositionStatus.CLIENT_PAID, Actor.SYSTEM);
        verify(lifecycle).transition(1L, PositionStatus.WITH_BUYER, Actor.SYSTEM);
        verify(lifecycle, never()).transition(2L, PositionStatus.CLIENT_PAID, Actor.SYSTEM);
        assertThat(events).containsExactly(new ProformaPaidEvent(5L, 9L, List.of(1L), null));

        // replay (concurrent link / retry): nothing changes
        service.onLinkChanged(5L);
        verify(lifecycle, times(1)).transition(1L, PositionStatus.CLIENT_PAID, Actor.SYSTEM);
        assertThat(events).hasSize(1);
    }

    @Test
    void removingTheOnlyPaymentBeforeReleaseReturnsToIssued() {
        proforma.setStatus(ProformaStatus.PARTIALLY_PAID);
        proforma.setPaidGross(new BigDecimal("50.00"));
        when(linkDao.sumAmountByClientProformaId(5L)).thenReturn(BigDecimal.ZERO);

        service.onLinkChanged(5L);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        assertThat(proforma.getPaidGross()).isEqualByComparingTo("0");
    }

    @Test
    void draftCancelledAndReleasedProformasAreNotTouched() {
        when(linkDao.sumAmountByClientProformaId(5L)).thenReturn(new BigDecimal("1.00"));
        for (ProformaStatus s : List.of(ProformaStatus.DRAFT, ProformaStatus.CANCELLED)) {
            proforma.setStatus(s);
            service.onLinkChanged(5L);
            assertThat(proforma.getStatus()).isEqualTo(s);
            assertThat(proforma.getPaidGross()).isEqualByComparingTo("0");
        }
        proforma.setStatus(ProformaStatus.PAID);
        proforma.setReleasedAt(OffsetDateTime.now());
        service.onLinkChanged(5L);
        verify(dao, never()).save(any());
    }

    private static PurchasePositionEntity position(Long id, PositionStatus status) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setStatus(status);
        return p;
    }

    private static ClientProformaLineEntity line(PurchasePositionEntity position) {
        ClientProformaLineEntity l = new ClientProformaLineEntity();
        l.setPosition(position);
        l.setActive(true);
        return l;
    }
}
