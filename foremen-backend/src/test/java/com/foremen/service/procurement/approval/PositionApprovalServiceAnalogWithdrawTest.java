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
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.PositionApprovalRoundServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.PositionApprovalService.AnalogCommand;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionVariantService;

import jakarta.persistence.EntityManager;

/** FOR-11-02 task 3.4: analog, suggestion decline, withdraw, deadline (R9, R10.3, R13). */
class PositionApprovalServiceAnalogWithdrawTest {

    private PositionApprovalRoundDao roundDao;
    private PositionApprovalOptionDao optionDao;
    private PurchasePositionDao positionDao;
    private FinishingMaterialDao materialDao;
    private PositionLifecycleService lifecycle;
    private ApplicationEventPublisher events;
    private PositionApprovalService service;

    @BeforeEach
    void setUp() {
        roundDao = mock(PositionApprovalRoundDao.class);
        optionDao = mock(PositionApprovalOptionDao.class);
        positionDao = mock(PurchasePositionDao.class);
        materialDao = mock(FinishingMaterialDao.class);
        lifecycle = mock(PositionLifecycleService.class);
        events = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2030-05-10T10:00:00Z"), ApprovalDeadlinePolicy.ZONE);
        service = new PositionApprovalService(roundDao, optionDao, positionDao, mock(PurchasePositionService.class),
                lifecycle, materialDao, mock(PositionVariantService.class), mock(ProjectMemberDao.class),
                new PositionApprovalRoundServiceMapper(), mock(AuditLogDao.class), mock(EntityManager.class),
                mock(ProcurementProjectAccess.class), events, clock);
        when(positionDao.save(any(PurchasePositionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(roundDao.maxRoundNo(anyLong())).thenReturn(1);
        when(roundDao.save(any(PositionApprovalRoundEntity.class))).thenAnswer(i -> {
            PositionApprovalRoundEntity r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(900L);
            }
            return r;
        });
        when(roundDao.findOpenByPositionId(anyLong(), eq(ApprovalRoundStatus.OPEN))).thenReturn(Optional.empty());
        when(materialDao.findById(202L)).thenReturn(Optional.of(material(202L)));
    }

    // --- proposeAnalog ---

    @Test
    void analogFromProposedSupersedesOpenRoundAndKeepsItsPreviousStatus() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity open = round(10L, p, ApprovalRoundKind.PROPOSAL);
        stubPosition(p);
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(open));

        Long id = service.proposeAnalog(1L, new AnalogCommand(202L, new BigDecimal("99.00"),
                new BigDecimal("70.00"), LocalDate.of(2030, 5, 15), " note ", null));

        assertThat(id).isEqualTo(900L);
        assertThat(open.getStatus()).isEqualTo(ApprovalRoundStatus.SUPERSEDED);
        ArgumentCaptor<PositionApprovalRoundEntity> saved = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        PositionApprovalRoundEntity analog = saved.getAllValues().stream()
                .filter(r -> r.getKind() == ApprovalRoundKind.ANALOG).findFirst().orElseThrow();
        assertThat(analog.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(analog.getPreviousStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(analog.getManagerNote()).isEqualTo("note");
        assertThat(analog.getDecisionDeadline()).isEqualTo(LocalDate.of(2030, 5, 15));
        ArgumentCaptor<PositionApprovalOptionEntity> opt = ArgumentCaptor.forClass(PositionApprovalOptionEntity.class);
        verify(optionDao).save(opt.capture());
        assertThat(opt.getValue().getUnitSalePrice()).isEqualByComparingTo("99.00");
        assertThat(opt.getValue().getInternalUnitPurchasePrice()).isEqualByComparingTo("70.00");
        assertThat(p.getDecisionDeadline()).isEqualTo(LocalDate.of(2030, 5, 15));
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events).publishEvent(new PositionAnalogProposedEvent(7L, 1L));
    }

    @Test
    void analogFromAcceptedMovesToProposedWithPreviousAcceptedAndResolvesSuggestion() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PositionApprovalRoundEntity suggestion = round(11L, p, ApprovalRoundKind.CLIENT_SUGGESTION);
        stubPosition(p);
        when(roundDao.findByIdForUpdate(11L)).thenReturn(Optional.of(suggestion));

        service.proposeAnalog(1L, new AnalogCommand(202L, new BigDecimal("99.00"), null, null, null, 11L));

        assertThat(suggestion.getStatus()).isEqualTo(ApprovalRoundStatus.RESOLVED);
        ArgumentCaptor<PositionApprovalRoundEntity> saved = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        PositionApprovalRoundEntity analog = saved.getAllValues().stream()
                .filter(r -> r.getKind() == ApprovalRoundKind.ANALOG).findFirst().orElseThrow();
        assertThat(analog.getPreviousStatus()).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(analog.getSourceSuggestion()).isSameAs(suggestion);
        verify(lifecycle).transition(1L, PositionStatus.PROPOSED, Actor.SYSTEM);
    }

    @Test
    void analogRejectsLateStageSameAsCurrentAndBadSuggestion() {
        PurchasePositionEntity late = position(2L, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);
        stubPosition(late);
        assertError(() -> service.proposeAnalog(2L, new AnalogCommand(202L, new BigDecimal("99"), null, null, null,
                null)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);

        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        stubPosition(p);
        when(materialDao.findById(101L)).thenReturn(Optional.of(material(101L)));
        assertError(() -> service.proposeAnalog(1L, new AnalogCommand(101L, new BigDecimal("120.0"), null, null,
                null, null)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_SAME_AS_CURRENT);

        PositionApprovalRoundEntity otherPosSuggestion =
                round(12L, position(5L, SourcingPath.AGREED, PositionStatus.PROPOSED),
                        ApprovalRoundKind.CLIENT_SUGGESTION);
        when(roundDao.findByIdForUpdate(12L)).thenReturn(Optional.of(otherPosSuggestion));
        assertError(() -> service.proposeAnalog(1L, new AnalogCommand(202L, new BigDecimal("99"), null, null, null,
                12L)), HttpStatus.NOT_FOUND, PositionApprovalService.ERROR_ROUND_NOT_FOUND);

        assertError(() -> service.proposeAnalog(1L, new AnalogCommand(202L, BigDecimal.ZERO, null, null, null,
                null)), HttpStatus.BAD_REQUEST, "error.invalid.field.name");
        assertError(() -> service.proposeAnalog(1L, new AnalogCommand(202L, new BigDecimal("99"), null,
                LocalDate.of(2030, 5, 9), null, null)), HttpStatus.BAD_REQUEST,
                ApprovalDeadlinePolicy.ERROR_INVALID);
        verify(optionDao, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    // --- declineSuggestion ---

    @Test
    void declineSuggestionStoresReasonAndRequiresOpenSuggestion() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity suggestion = round(11L, p, ApprovalRoundKind.CLIENT_SUGGESTION);
        when(roundDao.findByIdForUpdate(11L)).thenReturn(Optional.of(suggestion));

        assertError(() -> service.declineSuggestion(11L, " "), HttpStatus.BAD_REQUEST,
                PositionApprovalService.ERROR_COMMENT_REQUIRED);
        service.declineSuggestion(11L, " no stock ");

        assertThat(suggestion.getStatus()).isEqualTo(ApprovalRoundStatus.DECLINED);
        assertThat(suggestion.getManagerNote()).isEqualTo("no stock");
        assertError(() -> service.declineSuggestion(11L, "again"), HttpStatus.CONFLICT,
                PositionApprovalService.ERROR_ROUND_NOT_OPEN);
    }

    // --- withdraw ---

    @Test
    void withdrawRestoresPlannedOrAcceptedByPreviousStatusAndClearsDeadline() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = round(10L, p, ApprovalRoundKind.PROPOSAL);
        when(roundDao.findByIdForUpdate(10L)).thenReturn(Optional.of(round));
        service.withdraw(10L);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.WITHDRAWN);
        verify(lifecycle).transition(1L, PositionStatus.PLANNED, Actor.SYSTEM);
        assertThat(p.getDecisionDeadline()).isNull();

        PurchasePositionEntity q = position(2L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity analog = round(20L, q, ApprovalRoundKind.ANALOG);
        analog.setPreviousStatus(PositionStatus.ACCEPTED);
        when(roundDao.findByIdForUpdate(20L)).thenReturn(Optional.of(analog));
        service.withdraw(20L);
        verify(lifecycle).transition(2L, PositionStatus.ACCEPTED, Actor.SYSTEM);
        assertThat(q.getDecisionDeadline()).isNull();
    }

    @Test
    void withdrawPackageChoiceKeepsStatusAndNonOpenRoundIsRejected() {
        PurchasePositionEntity p = position(3L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PositionApprovalRoundEntity round = round(30L, p, ApprovalRoundKind.PACKAGE_CHOICE);
        when(roundDao.findByIdForUpdate(30L)).thenReturn(Optional.of(round));
        service.withdraw(30L);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.WITHDRAWN);
        assertThat(p.getDecisionDeadline()).isNull();

        assertError(() -> service.withdraw(30L), HttpStatus.CONFLICT, PositionApprovalService.ERROR_ROUND_NOT_OPEN);
    }

    // --- changeDeadline ---

    @Test
    void changeDeadlineMirrorsToPositionAllowsNullAndValidates() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = round(10L, p, ApprovalRoundKind.PROPOSAL);
        when(roundDao.findByIdForUpdate(10L)).thenReturn(Optional.of(round));

        service.changeDeadline(10L, LocalDate.of(2030, 6, 1));
        assertThat(round.getDecisionDeadline()).isEqualTo(LocalDate.of(2030, 6, 1));
        assertThat(p.getDecisionDeadline()).isEqualTo(LocalDate.of(2030, 6, 1));

        service.changeDeadline(10L, null);
        assertThat(round.getDecisionDeadline()).isNull();
        assertThat(p.getDecisionDeadline()).isNull();

        assertError(() -> service.changeDeadline(10L, LocalDate.of(2030, 5, 9)), HttpStatus.BAD_REQUEST,
                ApprovalDeadlinePolicy.ERROR_INVALID);
        round.setStatus(ApprovalRoundStatus.ACCEPTED);
        assertError(() -> service.changeDeadline(10L, LocalDate.of(2030, 6, 2)), HttpStatus.CONFLICT,
                PositionApprovalService.ERROR_ROUND_NOT_OPEN);
    }

    // --- fixtures ---

    private void stubPosition(PurchasePositionEntity p) {
        when(positionDao.findAllById(List.of(p.getId()))).thenReturn(List.of(p));
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    private static PositionApprovalRoundEntity round(Long id, PurchasePositionEntity p, ApprovalRoundKind kind) {
        PositionApprovalRoundEntity r = new PositionApprovalRoundEntity();
        r.setId(id);
        r.setPosition(p);
        r.setKind(kind);
        r.setStatus(ApprovalRoundStatus.OPEN);
        r.setRoundNo(1);
        r.setPreviousStatus(PositionStatus.PLANNED);
        return r;
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
        p.setPlannedMaterial(material(101L));
        p.setQuantity(new BigDecimal("4.000"));
        p.setSalePrice(new BigDecimal("120.00"));
        p.setDecisionDeadline(LocalDate.of(2030, 5, 20));
        return p;
    }

    private static FinishingMaterialEntity material(Long id) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setId(id);
        return m;
    }
}
