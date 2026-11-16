package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.planner.PositionMoneyMath;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-03 task 3.3 (R4.1 - R4.4, R4.8): the issue flow and the compensation of a stored PDF. */
class ProformaIssueServiceTest {

    private static final String KEY = "client-proformas/abc.pdf";

    private final ClientProformaDao dao = mock(ClientProformaDao.class);
    private final ClientProformaLineDao lineDao = mock(ClientProformaLineDao.class);
    private final ClientProformaService proformaService = mock(ClientProformaService.class);
    private final ClientProformaServiceMapper mapper = mock(ClientProformaServiceMapper.class);
    private final ProformaLineSnapshotBuilder snapshotBuilder = mock(ProformaLineSnapshotBuilder.class);
    private final ProformaNumberService numberService = mock(ProformaNumberService.class);
    private final ProformaPdfService pdfService = mock(ProformaPdfService.class);
    private final PositionLifecycleService lifecycle = mock(PositionLifecycleService.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EntityManager em = mock(EntityManager.class);
    private final com.foremen.service.EffectiveBankAccountResolver bankAccountResolver =
            mock(com.foremen.service.EffectiveBankAccountResolver.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"),
            com.foremen.service.procurement.PurchaseRequestOverdue.ZONE);

    private final ProformaIssueService service = new ProformaIssueService(dao, lineDao, proformaService,
            mapper, snapshotBuilder, numberService, pdfService, lifecycle, storage, events, em, bankAccountResolver, 7, clock);

    private ClientProformaEntity proforma;
    private PurchasePositionEntity position;
    private ClientProformaLineEntity oldLine;

    @BeforeEach
    void setUp() {
        ProjectEntity project = new ProjectEntity();
        project.setId(5L);
        proforma = new ClientProformaEntity();
        proforma.setId(1L);
        proforma.setProject(project);
        position = new PurchasePositionEntity();
        position.setId(11L);
        oldLine = new ClientProformaLineEntity();
        oldLine.setPosition(position);

        when(em.find(ClientProformaEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(proforma);
        when(lineDao.findByProformaIdOrderByLineNo(1L)).thenReturn(List.of(oldLine));
        when(proformaService.validatePositions(5L, List.of(11L), 1L)).thenReturn(List.of(position));
        when(numberService.next(LocalDate.of(2026, 3, 1))).thenReturn("PF/2026/000001");
        stubSnapshot("123.00");
    }

    private void stubSnapshot(String gross) {
        BigDecimal g = new BigDecimal(gross);
        when(snapshotBuilder.build(any(), anyList(), any())).thenReturn(new ProformaLineSnapshotBuilder.Snapshot(
                List.of(new ClientProformaLineEntity()), new PositionMoneyMath.Totals(g, BigDecimal.ZERO, g)));
    }

    @Test
    void issuesNumbersStoresPdfMovesPositionsAndPublishes() {
        when(pdfService.render(any(), anyList(), eq("pl"))).thenReturn(KEY);

        service.issue(1L, null);

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        assertThat(proforma.getNumber()).isEqualTo("PF/2026/000001");
        assertThat(proforma.getIssueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(proforma.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(proforma.getPdfObjectKey()).isEqualTo(KEY);
        assertThat(proforma.getPdfLanguage()).isEqualTo("pl");
        assertThat(proforma.getTotalGross()).isEqualByComparingTo("123.00");
        verify(lifecycle).transition(11L, PositionStatus.IN_PROFORMA, Actor.SYSTEM);
        verify(events).publishEvent(new ProformaIssuedEvent(1L));
        verify(storage, never()).deleteIfOrphan(any());
    }

    @Test
    void effectiveAccountIsFrozenOnThePaymentBeforeRendering() {
        proforma.getProject().setBankAccount("PL61109010140000071219812874");
        when(bankAccountResolver.forProject(proforma.getProject())).thenReturn("PL61 1090 1014 0000 0712 1981 2874");
        String[] seenAtRender = new String[1];
        when(pdfService.render(any(), anyList(), any())).thenAnswer(inv -> {
            seenAtRender[0] = proforma.getPayment().getBankAccountSnapshot();
            return KEY;
        });

        service.issue(1L, "pl");

        assertThat(seenAtRender[0]).isEqualTo("PL61109010140000071219812874");
        // a later project edit does not touch the stored snapshot
        proforma.getProject().setBankAccount("DE89370400440532013000");
        assertThat(proforma.getPayment().getBankAccountSnapshot()).isEqualTo("PL61109010140000071219812874");
    }

    @Test
    void noEffectiveAccountLeavesSnapshotNull() {
        when(bankAccountResolver.forProject(proforma.getProject())).thenReturn(null);
        when(pdfService.render(any(), anyList(), any())).thenReturn(KEY);

        service.issue(1L, "pl");

        assertThat(proforma.getPayment().getBankAccountSnapshot()).isNull();
    }

    @Test
    void clientUnpaidPositionKeepsItsStatusOnIssue() {
        position.setStatus(PositionStatus.RECEIVED);
        position.setClientUnpaid(true);
        when(pdfService.render(any(), anyList(), any())).thenReturn(KEY);

        service.issue(1L, "pl");

        assertThat(proforma.getStatus()).isEqualTo(ProformaStatus.ISSUED);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events).publishEvent(new ProformaIssuedEvent(1L));
    }

    @Test
    void failedPositionTransitionDeletesTheStoredPdf() {
        when(pdfService.render(any(), anyList(), any())).thenReturn(KEY);
        when(lifecycle.transition(anyLong(), any(), any()))
                .thenThrow(new ForemenApiException(HttpStatus.CONFLICT, "error.purchasePosition.transition.notAllowed"));

        assertThatThrownBy(() -> service.issue(1L, "pl")).isInstanceOf(ForemenApiException.class);

        verify(storage).deleteIfOrphan(KEY);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void failedPdfStoresNothingAndMovesNoPosition() {
        when(pdfService.render(any(), anyList(), any()))
                .thenThrow(new ForemenApiException(HttpStatus.BAD_GATEWAY, "error.proforma.pdf.failed"));

        assertThatThrownBy(() -> service.issue(1L, "pl"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(502));

        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(storage, never()).deleteIfOrphan(any());
    }

    @Test
    void zeroTotalIsRejectedBeforeAnyWrite() {
        stubSnapshot("0.00");

        assertThatThrownBy(() -> service.issue(1L, "pl"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("error.proforma.total.zero"));

        verify(numberService, never()).next(any());
        verify(pdfService, never()).render(any(), anyList(), any());
    }

    @Test
    void dueDateBeforeIssueDateIsRejected() {
        proforma.setDueDate(LocalDate.of(2026, 2, 28));

        assertThatThrownBy(() -> service.issue(1L, "pl"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    @Test
    void nonDraftIsRejected() {
        proforma.setStatus(ProformaStatus.ISSUED);

        assertThatThrownBy(() -> service.issue(1L, "pl"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("error.proforma.notDraft"));
    }
}
