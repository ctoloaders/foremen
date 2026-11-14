package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.PositionApprovalOptionDao;
import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.PositionApprovalRoundServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SendCommand;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;

/** FOR-11-02 task 3.2: send and send-choice (R6.1-6.7, R21.2). */
class PositionApprovalServiceSendTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 5, 10);

    private PositionApprovalRoundDao roundDao;
    private PositionApprovalOptionDao optionDao;
    private PurchasePositionDao positionDao;
    private PurchasePositionService positionService;
    private PositionLifecycleService lifecycle;
    private FinishingMaterialDao materialDao;
    private ApplicationEventPublisher events;
    private PositionApprovalService service;

    @BeforeEach
    void setUp() {
        roundDao = mock(PositionApprovalRoundDao.class);
        optionDao = mock(PositionApprovalOptionDao.class);
        positionDao = mock(PurchasePositionDao.class);
        positionService = mock(PurchasePositionService.class);
        lifecycle = mock(PositionLifecycleService.class);
        materialDao = mock(FinishingMaterialDao.class);
        events = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2030-05-10T10:00:00Z"), ApprovalDeadlinePolicy.ZONE);
        service = new PositionApprovalService(roundDao, optionDao, positionDao, positionService, lifecycle,
                materialDao, mock(com.foremen.service.procurement.position.PositionVariantService.class),
                mock(com.foremen.dao.ProjectMemberDao.class), new PositionApprovalRoundServiceMapper(), mock(AuditLogDao.class), mock(EntityManager.class),
                mock(ProcurementProjectAccess.class), events, clock);
        when(roundDao.maxRoundNo(anyLong())).thenReturn(0);
        when(roundDao.findOpenByPositionId(anyLong(), eq(ApprovalRoundStatus.OPEN))).thenReturn(Optional.empty());
        when(roundDao.save(any(PositionApprovalRoundEntity.class))).thenAnswer(i -> {
            PositionApprovalRoundEntity r = i.getArgument(0);
            r.setId(r.getPosition().getId() + 1000);
            return r;
        });
    }

    @Test
    void sendCreatesProposalRoundOptionMirrorsDeadlineAndMovesToProposed() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(p));
        LocalDate deadline = TODAY.plusDays(3);

        List<Long> ids = service.send(new SendCommand(List.of(1L), deadline, "note"));

        assertThat(ids).containsExactly(1001L);
        ArgumentCaptor<PositionApprovalRoundEntity> round = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao).save(round.capture());
        PositionApprovalRoundEntity r = round.getValue();
        assertThat(r.getKind()).isEqualTo(ApprovalRoundKind.PROPOSAL);
        assertThat(r.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(r.getRoundNo()).isEqualTo(1);
        assertThat(r.getPreviousStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(r.getBaseUnitSalePrice()).isEqualByComparingTo("120.00");
        assertThat(r.getDecisionDeadline()).isEqualTo(deadline);
        assertThat(r.getManagerNote()).isEqualTo("note");

        ArgumentCaptor<PositionApprovalOptionEntity> option = ArgumentCaptor.forClass(PositionApprovalOptionEntity.class);
        verify(optionDao).save(option.capture());
        assertThat(option.getValue().getUnitSalePrice()).isEqualByComparingTo("120.00");
        assertThat(option.getValue().getInternalUnitPurchasePrice()).isEqualByComparingTo("80.00");
        assertThat(option.getValue().getMaterial()).isSameAs(p.getPlannedMaterial());

        assertThat(p.getDecisionDeadline()).isEqualTo(deadline);
        verify(lifecycle).transition(1L, PositionStatus.PROPOSED, Actor.SYSTEM);
        verify(events).publishEvent(new PositionsSentForDecisionEvent(7L, List.of(1L)));
    }

    @Test
    void sendIsAllOrNothingAndNamesTheOffendingPosition() {
        PurchasePositionEntity ok = position(1L, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity bad = position(2L, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        when(positionDao.findAllById(List.of(1L, 2L))).thenReturn(List.of(ok, bad));

        assertThatThrownBy(() -> service.send(new SendCommand(List.of(1L, 2L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE);
                    assertThat(e.getMessageParams()).containsExactly(2L);
                });
        verify(roundDao, never()).save(any());
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void sendRejectsPositionWithOpenRoundAndMissingPrice() {
        PurchasePositionEntity open = position(1L, SourcingPath.AGREED, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(open));
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN))
                .thenReturn(Optional.of(new PositionApprovalRoundEntity()));
        assertThatThrownBy(() -> service.send(new SendCommand(List.of(1L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE));

        PurchasePositionEntity noPrice = position(3L, SourcingPath.AGREED, PositionStatus.PLANNED);
        noPrice.setSalePrice(null);
        when(positionDao.findAllById(List.of(3L))).thenReturn(List.of(noPrice));
        assertThatThrownBy(() -> service.send(new SendCommand(List.of(3L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_SEND_PRICE_MISSING));
    }

    @Test
    void sendRejectsInactiveProjectAndPastDeadline() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PLANNED);
        p.getProject().setStatus(ProjectStatus.ON_HOLD);
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(p));
        assertThatThrownBy(() -> service.send(new SendCommand(List.of(1L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE));

        assertThatThrownBy(() -> service.send(new SendCommand(List.of(1L), TODAY.minusDays(1), null)))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessageCode()).isEqualTo(ApprovalDeadlinePolicy.ERROR_INVALID);
                });
    }

    @Test
    void sendChoiceCreatesPriceLessOptionsAndKeepsPositionStatus() {
        PurchasePositionEntity p = position(5L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(5L))).thenReturn(List.of(p));
        when(materialDao.findById(10L)).thenReturn(Optional.of(material(10L)));
        when(materialDao.findById(11L)).thenReturn(Optional.of(material(11L)));

        Long id = service.sendChoice(new SendChoiceCommand(5L, List.of(10L, 11L), TODAY, null));

        assertThat(id).isEqualTo(1005L);
        ArgumentCaptor<PositionApprovalRoundEntity> round = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao).save(round.capture());
        assertThat(round.getValue().getKind()).isEqualTo(ApprovalRoundKind.PACKAGE_CHOICE);
        assertThat(round.getValue().getBaseUnitSalePrice()).isNull();
        ArgumentCaptor<PositionApprovalOptionEntity> options = ArgumentCaptor.forClass(PositionApprovalOptionEntity.class);
        verify(optionDao, org.mockito.Mockito.times(2)).save(options.capture());
        assertThat(options.getAllValues()).allSatisfy(o -> {
            assertThat(o.getUnitSalePrice()).isNull();
            assertThat(o.getInternalUnitPurchasePrice()).isNull();
        });
        assertThat(options.getAllValues()).extracting(PositionApprovalOptionEntity::getSortOrder).containsExactly(0, 1);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events).publishEvent(new PositionsSentForDecisionEvent(7L, List.of(5L)));
    }

    @Test
    void sendChoiceRejectsBadOptionSetsAndWrongPath() {
        for (List<Long> bad : List.of(List.of(10L), List.of(10L, 10L), List.of(1L, 2L, 3L, 4L, 5L, 6L))) {
            assertThatThrownBy(() -> service.sendChoice(new SendChoiceCommand(5L, bad, null, null)))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_OPTIONS_INVALID);
                    });
        }
        PurchasePositionEntity agreed = position(6L, SourcingPath.AGREED, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(6L))).thenReturn(List.of(agreed));
        assertThatThrownBy(() -> service.sendChoice(new SendChoiceCommand(6L, List.of(10L, 11L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE));
        verify(roundDao, never()).save(any());
    }

    @Test
    void sendChoiceRejectsMaterialOfAnotherProject() {
        PurchasePositionEntity p = position(5L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(5L))).thenReturn(List.of(p));
        FinishingMaterialEntity foreign = material(10L);
        ProjectEntity other = new ProjectEntity();
        other.setId(99L);
        foreign.setProject(other);
        when(materialDao.findById(10L)).thenReturn(Optional.of(foreign));
        when(materialDao.findById(11L)).thenReturn(Optional.of(material(11L)));

        assertThatThrownBy(() -> service.sendChoice(new SendChoiceCommand(5L, List.of(10L, 11L), null, null)))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> assertThat(e.getMessageCode())
                        .isEqualTo(PositionApprovalService.ERROR_MATERIAL_PROJECT_MISMATCH));
        verify(roundDao, never()).save(any());
    }

    @Test
    void projectIdPathIsPositionProjectId() {
        assertThat(service.getProjectIdPath()).isEqualTo("position.project.id");
    }

    private static PurchasePositionEntity position(Long id, SourcingPath path, PositionStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setStatus(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project);
        p.setSourcingPath(path);
        p.setStatus(status);
        p.setPlannedMaterial(material(100L + id));
        p.setQuantity(new BigDecimal("4.000"));
        p.setSalePrice(new BigDecimal("120.00"));
        p.setPlannedPurchasePrice(new BigDecimal("80.00"));
        VatRateEntity vat = new VatRateEntity();
        vat.setRate(new BigDecimal("8"));
        p.setSaleVatRate(vat);
        return p;
    }

    private static FinishingMaterialEntity material(Long id) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setId(id);
        return m;
    }
}
