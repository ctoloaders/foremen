package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.PositionApprovalOptionDao;
import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VariantReason;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.PositionApprovalRoundServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SuggestCommand;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionVariantService;
import com.foremen.service.procurement.position.ReplaceCommand;

import jakarta.persistence.EntityManager;

/** FOR-11-02 task 3.3: client decisions, shared with the on-behalf path (R7, R8, R10, R11, R12). */
class PositionApprovalServiceDecisionTest {

    private static final Long CLIENT_ID = 50L;
    private static final Long MANAGER_ID = 60L;

    private PositionApprovalRoundDao roundDao;
    private PositionApprovalOptionDao optionDao;
    private PurchasePositionDao positionDao;
    private PositionLifecycleService lifecycle;
    private PositionVariantService variants;
    private ProjectMemberDao memberDao;
    private ApplicationEventPublisher events;
    private PositionApprovalService service;

    @BeforeEach
    void setUp() {
        roundDao = mock(PositionApprovalRoundDao.class);
        optionDao = mock(PositionApprovalOptionDao.class);
        positionDao = mock(PurchasePositionDao.class);
        PurchasePositionService positionService = mock(PurchasePositionService.class);
        lifecycle = mock(PositionLifecycleService.class);
        variants = mock(PositionVariantService.class);
        memberDao = mock(ProjectMemberDao.class);
        events = mock(ApplicationEventPublisher.class);
        EntityManager em = mock(EntityManager.class);
        when(em.getReference(eq(UserEntity.class), anyLong())).thenAnswer(i -> {
            UserEntity u = new UserEntity();
            u.setId(i.getArgument(1));
            return u;
        });
        Clock clock = Clock.fixed(Instant.parse("2030-05-10T10:00:00Z"), ApprovalDeadlinePolicy.ZONE);
        service = new PositionApprovalService(roundDao, optionDao, positionDao, positionService, lifecycle,
                mock(FinishingMaterialDao.class), variants, memberDao, new PositionApprovalRoundServiceMapper(),
                mock(AuditLogDao.class), em, mock(ProcurementProjectAccess.class), events, clock);
        when(positionDao.save(any(PurchasePositionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(positionDao.findAllById(any())).thenAnswer(i -> List.of());
        when(roundDao.maxRoundNo(anyLong())).thenReturn(1);
        when(roundDao.save(any(PositionApprovalRoundEntity.class))).thenAnswer(i -> {
            PositionApprovalRoundEntity r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(900L);
            }
            return r;
        });
        when(roundDao.findOpenByPositionId(anyLong(), eq(ApprovalRoundStatus.OPEN))).thenReturn(Optional.empty());
        member(CLIENT_ID, "CLIENT", AssignmentStatus.ACTIVE);
    }

    // --- accept ---

    @Test
    void acceptProposalLocksRoundMovesToAcceptedAndRecordsDecision() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PROPOSAL);
        PositionApprovalOptionEntity option = option(100L, round, 101L, "120.00");
        stub(round, option);

        service.accept(10L, new AcceptCommand(null, new BigDecimal("120.0"), "ok"), Decider.client(CLIENT_ID));

        InOrder order = inOrder(roundDao, lifecycle);
        order.verify(roundDao).findByIdForUpdate(10L);
        order.verify(lifecycle).transition(1L, PositionStatus.ACCEPTED, Actor.SYSTEM);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
        assertThat(round.getChosenOption()).isSameAs(option);
        assertThat(round.getDecidedSide()).isEqualTo(DecidedSide.CLIENT);
        assertThat(round.isOnBehalf()).isFalse();
        assertThat(round.getDecidedBy().getId()).isEqualTo(CLIENT_ID);
        assertThat(round.getDecidedAt()).isNotNull();
        assertThat(round.getDecisionComment()).isEqualTo("ok");
        assertThat(p.getDecisionDeadline()).isNull();
        verify(variants, never()).replace(anyLong(), any(), any());
        verify(events).publishEvent(new PositionDecisionEvent(7L, 1L, PositionDecisionEvent.Outcome.ACCEPTED,
                false, CLIENT_ID));
    }

    @Test
    void acceptWithOtherDisplayedPriceIsPriceChangedAndRoundStaysOpen() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PROPOSAL);
        stub(round, option(100L, round, 101L, "120.00"));

        assertError(() -> service.accept(10L, new AcceptCommand(null, new BigDecimal("119.99"), null),
                Decider.client(CLIENT_ID)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_PRICE_CHANGED);
        assertError(() -> service.accept(10L, new AcceptCommand(null, null, null), Decider.client(CLIENT_ID)),
                HttpStatus.CONFLICT, PositionApprovalService.ERROR_PRICE_CHANGED);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void acceptProposalIsStaleWhenPositionPriceOrMaterialMovedOn() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PROPOSAL);
        stub(round, option(100L, round, 101L, "120.00"));
        p.setSalePrice(new BigDecimal("130.00"));

        assertError(() -> service.accept(10L, new AcceptCommand(null, new BigDecimal("120.00"), null),
                Decider.client(CLIENT_ID)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_STALE);

        p.setSalePrice(new BigDecimal("120.00"));
        p.setPlannedMaterial(material(555L));
        assertError(() -> service.accept(10L, new AcceptCommand(null, new BigDecimal("120.00"), null),
                Decider.client(CLIENT_ID)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_STALE);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void acceptAnalogReplacesItemBeforeAcceptingWithSystemActor() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.ANALOG);
        round.setManagerNote("analog note");
        PositionApprovalOptionEntity option = option(100L, round, 202L, "99.00");
        option.setInternalUnitPurchasePrice(new BigDecimal("70.00"));
        stub(round, option);

        service.accept(10L, new AcceptCommand(100L, new BigDecimal("99.00"), null), Decider.client(CLIENT_ID));

        InOrder order = inOrder(variants, lifecycle);
        order.verify(variants).replace(1L, new ReplaceCommand(202L, new BigDecimal("99.00"),
                new BigDecimal("70.00"), VariantReason.ANALOG, "analog note"), Actor.SYSTEM);
        order.verify(lifecycle).transition(1L, PositionStatus.ACCEPTED, Actor.SYSTEM);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
    }

    @Test
    void acceptPackageChoiceTakesNoPriceKeepsStatusAndReplacesMaterial() {
        PurchasePositionEntity p = position(1L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PACKAGE_CHOICE);
        PositionApprovalOptionEntity a = option(100L, round, 301L, null);
        PositionApprovalOptionEntity b = option(101L, round, 302L, null);
        stub(round, a, b);

        assertError(() -> service.accept(10L, new AcceptCommand(null, null, null), Decider.client(CLIENT_ID)),
                HttpStatus.BAD_REQUEST, PositionApprovalService.ERROR_OPTION_INVALID);
        assertError(() -> service.accept(10L, new AcceptCommand(100L, new BigDecimal("1"), null),
                Decider.client(CLIENT_ID)), HttpStatus.BAD_REQUEST,
                PositionApprovalService.ERROR_OPTION_PRICE_NOT_ALLOWED);

        service.accept(10L, new AcceptCommand(101L, null, null), Decider.client(CLIENT_ID));

        verify(variants).replace(1L, new ReplaceCommand(302L, null, null, VariantReason.ANALOG, null), Actor.SYSTEM);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        assertThat(round.getChosenOption()).isSameAs(b);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
    }

    @Test
    void acceptRejectsClosedRoundWrongStageAndNonMember() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity closed = open(10L, p, ApprovalRoundKind.PROPOSAL);
        closed.setStatus(ApprovalRoundStatus.WITHDRAWN);
        stub(closed, option(100L, closed, 101L, "120.00"));
        assertError(() -> service.accept(10L, new AcceptCommand(null, new BigDecimal("120"), null),
                Decider.client(CLIENT_ID)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_ROUND_NOT_OPEN);

        PurchasePositionEntity inProforma = position(2L, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);
        PositionApprovalRoundEntity late = open(11L, inProforma, ApprovalRoundKind.PROPOSAL);
        stub(late, option(101L, late, 101L, "120.00"));
        assertError(() -> service.accept(11L, new AcceptCommand(null, new BigDecimal("120"), null),
                Decider.client(CLIENT_ID)), HttpStatus.CONFLICT, PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);

        PositionApprovalRoundEntity ok = open(12L, p, ApprovalRoundKind.PROPOSAL);
        stub(ok, option(102L, ok, 101L, "120.00"));
        member(CLIENT_ID, "FOREMAN", AssignmentStatus.ACTIVE);
        assertError(() -> service.accept(12L, new AcceptCommand(null, new BigDecimal("120"), null),
                Decider.client(CLIENT_ID)), HttpStatus.NOT_FOUND, PositionApprovalService.ENTITY_NOT_FOUND);
        member(CLIENT_ID, "CLIENT", AssignmentStatus.INACTIVE);
        assertError(() -> service.accept(12L, new AcceptCommand(null, new BigDecimal("120"), null),
                Decider.client(CLIENT_ID)), HttpStatus.NOT_FOUND, PositionApprovalService.ENTITY_NOT_FOUND);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void unknownRoundIsNotFound() {
        when(roundDao.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        assertError(() -> service.accept(99L, new AcceptCommand(null, null, null), Decider.client(CLIENT_ID)),
                HttpStatus.NOT_FOUND, PositionApprovalService.ERROR_ROUND_NOT_FOUND);
    }

    // --- reject ---

    @Test
    void rejectRequiresCommentThenMovesToPlannedAndClearsDeadline() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PROPOSAL);
        stub(round, option(100L, round, 101L, "120.00"));

        for (String bad : new String[] {null, "  ", "x".repeat(1001)}) {
            assertError(() -> service.reject(10L, bad, Decider.client(CLIENT_ID)), HttpStatus.BAD_REQUEST,
                    PositionApprovalService.ERROR_COMMENT_REQUIRED);
        }
        service.reject(10L, " too expensive ", Decider.client(CLIENT_ID));

        verify(lifecycle).transition(1L, PositionStatus.PLANNED, Actor.SYSTEM);
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.REJECTED);
        assertThat(round.getDecisionComment()).isEqualTo("too expensive");
        assertThat(p.getDecisionDeadline()).isNull();
        verify(events).publishEvent(new PositionDecisionEvent(7L, 1L, PositionDecisionEvent.Outcome.REJECTED,
                false, CLIENT_ID));
    }

    @Test
    void rejectPackageChoiceKeepsPositionStatus() {
        PurchasePositionEntity p = position(1L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PACKAGE_CHOICE);
        stub(round, option(100L, round, 301L, null), option(101L, round, 302L, null));

        service.reject(10L, "none fits", Decider.client(CLIENT_ID));

        verify(lifecycle, never()).transition(anyLong(), any(), any());
        assertThat(round.getStatus()).isEqualTo(ApprovalRoundStatus.REJECTED);
    }

    // --- on behalf (same code path) ---

    @Test
    void onBehalfAcceptNeedsCommentSkipsMembershipAndFlagsTheRound() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        PositionApprovalRoundEntity round = open(10L, p, ApprovalRoundKind.PROPOSAL);
        stub(round, option(100L, round, 101L, "120.00"));
        AcceptCommand noComment = new AcceptCommand(null, new BigDecimal("120.00"), " ");

        assertError(() -> service.accept(10L, noComment, Decider.staffOnBehalf(MANAGER_ID)), HttpStatus.BAD_REQUEST,
                PositionApprovalService.ERROR_COMMENT_REQUIRED);

        service.accept(10L, new AcceptCommand(null, new BigDecimal("120.00"), "phone call"),
                Decider.staffOnBehalf(MANAGER_ID));

        assertThat(round.getDecidedSide()).isEqualTo(DecidedSide.STAFF);
        assertThat(round.isOnBehalf()).isTrue();
        assertThat(round.getDecisionComment()).isEqualTo("phone call");
        verify(memberDao, never()).findByUserIdAndProjectId(eq(MANAGER_ID), anyLong());
        verify(events).publishEvent(new PositionDecisionEvent(7L, 1L, PositionDecisionEvent.Outcome.ACCEPTED,
                true, MANAGER_ID));
    }

    // --- suggest ---

    @Test
    void suggestCreatesOpenSuggestionRoundWithoutTouchingPosition() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(p));

        Long id = service.suggest(1L, new SuggestCommand(" https://shop.example/item ", "this one"),
                Decider.client(CLIENT_ID));

        assertThat(id).isEqualTo(900L);
        ArgumentCaptor<PositionApprovalRoundEntity> saved = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao).save(saved.capture());
        PositionApprovalRoundEntity r = saved.getValue();
        assertThat(r.getKind()).isEqualTo(ApprovalRoundKind.CLIENT_SUGGESTION);
        assertThat(r.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(r.getSuggestionLink()).isEqualTo("https://shop.example/item");
        assertThat(r.getDecisionComment()).isEqualTo("this one");
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(events).publishEvent(new PositionSuggestionEvent(7L, 1L));
    }

    @Test
    void suggestRejectsBadLinksAndWrongStage() {
        for (String bad : new String[] {null, "", "ftp://x.pl/a", "not a url", "/relative", "http://"}) {
            assertError(() -> service.suggest(1L, new SuggestCommand(bad, null), Decider.client(CLIENT_ID)),
                    HttpStatus.BAD_REQUEST, PositionApprovalService.ERROR_LINK_INVALID);
        }
        PurchasePositionEntity planned = position(2L, SourcingPath.AGREED, PositionStatus.PLANNED);
        when(positionDao.findAllById(List.of(2L))).thenReturn(List.of(planned));
        assertError(() -> service.suggest(2L, new SuggestCommand("https://a.pl/x", null), Decider.client(CLIENT_ID)),
                HttpStatus.CONFLICT, PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);
        verify(roundDao, never()).save(any());
    }

    // --- client own ---

    @Test
    void clientOwnFromAcceptedSupersedesRoundWalksSystemEdgesAndSwitchesPath() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        p.setDecisionDeadline(LocalDate.of(2030, 6, 1));
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(p));
        PositionApprovalRoundEntity openRound = open(10L, p, ApprovalRoundKind.PROPOSAL);
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(openRound));

        service.declareClientOwn(1L, "buy at OBI", Decider.client(CLIENT_ID));

        assertThat(openRound.getStatus()).isEqualTo(ApprovalRoundStatus.SUPERSEDED);
        ArgumentCaptor<PositionApprovalRoundEntity> saved = ArgumentCaptor.forClass(PositionApprovalRoundEntity.class);
        verify(roundDao, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        PositionApprovalRoundEntity declared = saved.getAllValues().stream()
                .filter(r -> r.getKind() == ApprovalRoundKind.CLIENT_OWN).findFirst().orElseThrow();
        assertThat(declared.getStatus()).isEqualTo(ApprovalRoundStatus.DECLARED);
        assertThat(declared.getDecidedSide()).isEqualTo(DecidedSide.CLIENT);
        assertThat(declared.getDecisionComment()).isEqualTo("buy at OBI");
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).transition(1L, PositionStatus.PROPOSED, Actor.SYSTEM);
        order.verify(lifecycle).transition(1L, PositionStatus.PLANNED, Actor.SYSTEM);
        assertThat(p.getSourcingPath()).isEqualTo(SourcingPath.CLIENT_OWN);
        assertThat(p.getDecisionDeadline()).isNull();
        verify(events).publishEvent(new PositionDecisionEvent(7L, 1L, PositionDecisionEvent.Outcome.CLIENT_OWN,
                false, CLIENT_ID));
    }

    @Test
    void clientOwnFromProposedUsesSingleEdgeAndOnBehalfNeedsComment() {
        PurchasePositionEntity p = position(1L, SourcingPath.AGREED, PositionStatus.PROPOSED);
        when(positionDao.findAllById(List.of(1L))).thenReturn(List.of(p));

        assertError(() -> service.declareClientOwn(1L, null, Decider.staffOnBehalf(MANAGER_ID)),
                HttpStatus.BAD_REQUEST, PositionApprovalService.ERROR_COMMENT_REQUIRED);
        service.declareClientOwn(1L, "chat", Decider.staffOnBehalf(MANAGER_ID));

        verify(lifecycle, never()).transition(1L, PositionStatus.PROPOSED, Actor.SYSTEM);
        verify(lifecycle).transition(1L, PositionStatus.PLANNED, Actor.SYSTEM);
        verify(events).publishEvent(new PositionDecisionEvent(7L, 1L, PositionDecisionEvent.Outcome.CLIENT_OWN,
                true, MANAGER_ID));
    }

    @Test
    void clientOwnRejectsPackageAndLateStages() {
        PurchasePositionEntity pkg = position(2L, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PurchasePositionEntity late = position(3L, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);
        when(positionDao.findAllById(List.of(2L))).thenReturn(List.of(pkg));
        when(positionDao.findAllById(List.of(3L))).thenReturn(List.of(late));

        for (Long id : List.of(2L, 3L)) {
            assertError(() -> service.declareClientOwn(id, null, Decider.client(CLIENT_ID)), HttpStatus.CONFLICT,
                    PositionApprovalService.ERROR_CLIENT_OWN_NOT_ALLOWED);
        }
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        assertThat(pkg.getSourcingPath()).isEqualTo(SourcingPath.PACKAGE);
    }

    // --- fixtures ---

    private void stub(PositionApprovalRoundEntity round, PositionApprovalOptionEntity... options) {
        when(roundDao.findByIdForUpdate(round.getId())).thenReturn(Optional.of(round));
        when(optionDao.findByRoundIdOrderBySortOrderAscIdAsc(round.getId())).thenReturn(List.of(options));
    }

    private void member(Long userId, String roleCode, AssignmentStatus status) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setProjectId(7L);
        m.setProjectRole(role);
        m.setAssignmentStatus(status);
        when(memberDao.findByUserIdAndProjectId(userId, 7L)).thenReturn(Optional.of(m));
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    private static PositionApprovalRoundEntity open(Long id, PurchasePositionEntity p, ApprovalRoundKind kind) {
        PositionApprovalRoundEntity r = new PositionApprovalRoundEntity();
        r.setId(id);
        r.setPosition(p);
        r.setKind(kind);
        r.setStatus(ApprovalRoundStatus.OPEN);
        r.setRoundNo(1);
        r.setBaseMaterial(p.getPlannedMaterial());
        r.setBaseUnitSalePrice(kind == ApprovalRoundKind.PACKAGE_CHOICE ? null : p.getSalePrice());
        r.setPreviousStatus(PositionStatus.PLANNED);
        return r;
    }

    private static PositionApprovalOptionEntity option(Long id, PositionApprovalRoundEntity round, Long materialId,
                                                       String price) {
        PositionApprovalOptionEntity o = new PositionApprovalOptionEntity();
        o.setId(id);
        o.setRound(round);
        o.setMaterial(material(materialId));
        o.setUnitSalePrice(price == null ? null : new BigDecimal(price));
        return o;
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
