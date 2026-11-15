package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-03 task 3.4 (R5.1 - R5.4). */
class ProformaCancelServiceTest {

    private final ClientProformaDao dao = mock(ClientProformaDao.class);
    private final ClientProformaLineDao lineDao = mock(ClientProformaLineDao.class);
    private final ClientProformaService proformaService = mock(ClientProformaService.class);
    private final ClientProformaServiceMapper mapper = mock(ClientProformaServiceMapper.class);
    private final PositionLifecycleService lifecycle = mock(PositionLifecycleService.class);
    private final ProformaLinkLookup links = mock(ProformaLinkLookup.class);
    private final EntityManager em = mock(EntityManager.class);
    private final ProformaCancelService service =
            new ProformaCancelService(dao, lineDao, proformaService, mapper, lifecycle, links, em);

    private ClientProformaEntity proforma;

    @BeforeEach
    void setUp() {
        proforma = new ClientProformaEntity();
        proforma.setId(5L);
        when(em.find(ClientProformaEntity.class, 5L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(proforma);
        when(lineDao.findByProformaIdOrderByLineNo(5L)).thenReturn(List.of(line(1L), line(2L)));
    }

    @Test
    void cancelsIssuedProformaAndReturnsPositionsToAccepted() {
        proforma.setStatus(ProformaStatus.ISSUED);

        service.cancel(5L, "  klient zrezygnował ");

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.CANCELLED);
        assertThat(proforma.getCancelledAt()).isNotNull();
        assertThat(proforma.getCancelReason()).isEqualTo("klient zrezygnował");
        verify(lineDao).deactivateByProformaId(5L);
        verify(lifecycle).transition(1L, PositionStatus.ACCEPTED, Actor.SYSTEM);
        verify(lifecycle).transition(2L, PositionStatus.ACCEPTED, Actor.SYSTEM);
    }

    @Test
    void cancelsDraftWithoutReasonAndWithoutTouchingPositions() {
        proforma.setStatus(ProformaStatus.DRAFT);

        service.cancel(5L, null);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.CANCELLED);
        verify(lineDao).deactivateByProformaId(5L);
        verify(lifecycle, never()).transition(any(), any(), any());
        verify(links, never()).hasLinks(any());
    }

    @Test
    void issuedNeedsAReasonUpTo500Characters() {
        proforma.setStatus(ProformaStatus.ISSUED);

        assertCode(() -> service.cancel(5L, " "), HttpStatus.BAD_REQUEST, ProformaCancelService.REASON_REQUIRED);
        assertCode(() -> service.cancel(5L, "x".repeat(501)), HttpStatus.BAD_REQUEST,
                ProformaCancelService.REASON_REQUIRED);
        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.ISSUED);
    }

    @Test
    void refusesWhenPaidOrLinked() {
        proforma.setStatus(ProformaStatus.ISSUED);
        proforma.setPaidGross(new BigDecimal("0.01"));
        assertCode(() -> service.cancel(5L, "r"), HttpStatus.CONFLICT, ProformaCancelService.HAS_PAYMENTS);

        proforma.setPaidGross(BigDecimal.ZERO);
        when(links.hasLinks(5L)).thenReturn(true);
        assertCode(() -> service.cancel(5L, "r"), HttpStatus.CONFLICT, ProformaCancelService.HAS_PAYMENTS);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        verify(lineDao, never()).deactivateByProformaId(any());
        verify(lifecycle, never()).transition(any(), any(), any());
    }

    @Test
    void cancelledIsTerminalAndPaidStatesAreRefused() {
        proforma.setStatus(ProformaStatus.CANCELLED);
        assertCode(() -> service.cancel(5L, "r"), HttpStatus.CONFLICT, ProformaIssueService.STATUS_NOT_ALLOWED);

        proforma.setStatus(ProformaStatus.PAID);
        assertCode(() -> service.cancel(5L, "r"), HttpStatus.CONFLICT, ProformaIssueService.STATUS_NOT_ALLOWED);
        verify(lifecycle, never()).transition(eq(1L), any(), any());
    }

    private static ClientProformaLineEntity line(long positionId) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(positionId);
        ClientProformaLineEntity l = new ClientProformaLineEntity();
        l.setPosition(p);
        return l;
    }

    private static void assertCode(Runnable call, HttpStatus status, String key) {
        ForemenApiException ex = catchThrowableOfType(ForemenApiException.class, call::run);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getMessageCode()).isEqualTo(key);
    }
}
