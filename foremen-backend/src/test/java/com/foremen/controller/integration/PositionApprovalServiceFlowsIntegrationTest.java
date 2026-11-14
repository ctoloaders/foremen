package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.foremen.dao.PositionApprovalOptionDao;
import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.AnalogCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SendCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SuggestCommand;

/**
 * FOR-11-02 task 8.1 - {@link PositionApprovalService} flows end to end against PostgreSQL: send, accept,
 * reject and resend, analog (from PROPOSED and ACCEPTED), package choice, client own, suggestion resolve and
 * decline, withdraw, deadline change, the one-open-round index, the IN_PROFORMA stage guard and
 * priceChanged / stale.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction (the test DDL index below is
 * rolled back with it). Liquibase is off in this profile, so the partial unique index of changeset 234 is
 * created in {@link #createOpenRoundIndex()} with the same DDL.
 *
 * <p>Validates Requirements 2.4, 2.5, 6.1, 6.2, 7.1, 7.2, 7.3, 7.4, 8.2, 9.2, 9.4, 11.2, 12.2, 13.1, 14.4.
 */
class PositionApprovalServiceFlowsIntegrationTest extends ProcurementIntegrationTestBase {

    @Autowired
    private PositionApprovalService service;
    @Autowired
    private PositionApprovalRoundDao roundDao;
    @Autowired
    private PositionApprovalOptionDao optionDao;

    private ProjectEntity project;
    private UserEntity client;
    private Decider clientDecider;

    @BeforeEach
    void setUpProject() {
        createOpenRoundIndex();
        project = project(ProjectStatus.ACTIVE);
        client = user("CLIENT");
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(client);
        pm.setProjectId(project.getId());
        pm.setProjectRole(role("CLIENT"));
        em.persist(pm);
        em.flush();
        projectAccessCache.invalidate(client.getId());
        clientDecider = Decider.client(client.getId());
    }

    private void createOpenRoundIndex() {
        em.createNativeQuery("CREATE UNIQUE INDEX IF NOT EXISTS uq_par_open_per_position "
                + "ON position_approval_rounds (position_id) WHERE status = 'OPEN' AND kind <> 'CLIENT_SUGGESTION'")
                .executeUpdate();
    }

    private static LocalDate deadline() {
        return LocalDate.now().plusDays(7);
    }

    private PurchasePositionEntity agreed(PositionStatus status) {
        return position(project, SourcingPath.AGREED, status);
    }

    private Long send(PurchasePositionEntity p) {
        Long id = service.send(new SendCommand(List.of(p.getId()), deadline(), "note")).get(0);
        em.flush();
        em.refresh(p);
        return id;
    }

    private PositionApprovalRoundEntity round(Long id) {
        em.flush();
        em.clear();
        return roundDao.findById(id).orElseThrow();
    }

    private PurchasePositionEntity reload(PurchasePositionEntity p) {
        em.flush();
        em.clear();
        return em.find(PurchasePositionEntity.class, p.getId());
    }

    private AcceptCommand acceptAt(String price) {
        return new AcceptCommand(null, new BigDecimal(price), null);
    }

    private void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    // --- send / accept / reject ---

    @Test
    @DisplayName("send -> accept: PLANNED -> PROPOSED -> ACCEPTED, round ACCEPTED, deadline cleared")
    void sendAccept() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long roundId = send(p);
        assertThat(p.getStatus()).isEqualTo(PositionStatus.PROPOSED);
        assertThat(p.getDecisionDeadline()).isEqualTo(deadline());

        PositionApprovalRoundEntity open = round(roundId);
        assertThat(open.getKind()).isEqualTo(ApprovalRoundKind.PROPOSAL);
        assertThat(open.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(open.getRoundNo()).isEqualTo(1);
        assertThat(optionDao.findByRoundIdOrderBySortOrderAscIdAsc(roundId)).hasSize(1);

        service.accept(roundId, acceptAt("100.00"), clientDecider);

        PositionApprovalRoundEntity closed = round(roundId);
        assertThat(closed.getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
        assertThat(closed.getDecidedAt()).isNotNull();
        assertThat(closed.isOnBehalf()).isFalse();
        assertThat(closed.getChosenOption()).isNotNull();
        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(after.getDecisionDeadline()).isNull();
    }

    @Test
    @DisplayName("send -> reject -> resend: reject returns to PLANNED, a second round gets round_no 2")
    void sendRejectResend() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long first = send(p);

        service.reject(first, "too expensive", clientDecider);
        assertThat(round(first).getStatus()).isEqualTo(ApprovalRoundStatus.REJECTED);
        assertThat(round(first).getDecisionComment()).isEqualTo("too expensive");
        PurchasePositionEntity back = reload(p);
        assertThat(back.getStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(back.getDecisionDeadline()).isNull();

        Long second = send(back);
        assertThat(round(second).getRoundNo()).isEqualTo(2);
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.PROPOSED);
    }

    @Test
    @DisplayName("reject without a comment is a 400; a non-client user cannot decide (404)")
    void rejectValidationAndMembership() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        assertThatThrownBy(() -> service.reject(id, " ", clientDecider)).isInstanceOfSatisfying(
                ForemenApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        UserEntity stranger = user("CLIENT");
        assertThatThrownBy(() -> service.accept(id, acceptAt("100.00"), Decider.client(stranger.getId())))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("accept on behalf is recorded with onBehalf=true and the STAFF side")
    void acceptOnBehalf() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        UserEntity manager = user("MANAGER");
        service.accept(id, new AcceptCommand(null, new BigDecimal("100.00"), "phone call"),
                Decider.staffOnBehalf(manager.getId()));
        PositionApprovalRoundEntity r = round(id);
        assertThat(r.isOnBehalf()).isTrue();
        assertThat(r.getDecidedSide().name()).isEqualTo("STAFF");
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.ACCEPTED);
    }

    // --- priceChanged / stale ---

    @Test
    @DisplayName("accept with a price different from the displayed one -> 409 priceChanged, nothing changes")
    void priceChanged() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        assertConflict(() -> service.accept(id, acceptAt("99.00"), clientDecider),
                PositionApprovalService.ERROR_PRICE_CHANGED);
        assertThat(round(id).getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.PROPOSED);
    }

    @Test
    @DisplayName("accept after the position sale price changed -> 409 stale")
    void stale() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        p.setSalePrice(new BigDecimal("120.00"));
        em.flush();
        assertConflict(() -> service.accept(id, acceptAt("100.00"), clientDecider),
                PositionApprovalService.ERROR_STALE);
        assertThat(round(id).getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
    }

    // --- analog ---

    @Test
    @DisplayName("analog from PROPOSED supersedes the open round; accept replaces the material and price")
    void analogFromProposed() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long first = send(p);
        FinishingMaterialEntity analog = material(project);

        Long analogRound = service.proposeAnalog(p.getId(), new AnalogCommand(analog.getId(),
                new BigDecimal("90.00"), new BigDecimal("60.00"), deadline(), "cheaper", null));

        assertThat(round(first).getStatus()).isEqualTo(ApprovalRoundStatus.SUPERSEDED);
        PositionApprovalRoundEntity ar = round(analogRound);
        assertThat(ar.getKind()).isEqualTo(ApprovalRoundKind.ANALOG);
        assertThat(ar.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(ar.getRoundNo()).isEqualTo(2);
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.PROPOSED);

        service.accept(analogRound, acceptAt("90.00"), clientDecider);
        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(after.getPlannedMaterial().getId()).isEqualTo(analog.getId());
        assertThat(after.getSalePrice()).isEqualByComparingTo("90.00");
        assertThat(round(analogRound).getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
    }

    @Test
    @DisplayName("analog from ACCEPTED goes back to PROPOSED; withdraw restores ACCEPTED")
    void analogFromAcceptedThenWithdraw() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long first = send(p);
        service.accept(first, acceptAt("100.00"), clientDecider);
        em.flush();
        FinishingMaterialEntity analog = material(project);

        Long analogRound = service.proposeAnalog(p.getId(), new AnalogCommand(analog.getId(),
                new BigDecimal("95.00"), null, deadline(), null, null));
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.PROPOSED);
        assertThat(round(analogRound).getPreviousStatus()).isEqualTo(PositionStatus.ACCEPTED);

        service.withdraw(analogRound);
        assertThat(round(analogRound).getStatus()).isEqualTo(ApprovalRoundStatus.WITHDRAWN);
        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(after.getDecisionDeadline()).isNull();
    }

    @Test
    @DisplayName("analog identical to the current material and price -> 409 sameAsCurrent")
    void analogSameAsCurrent() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        send(p);
        assertConflict(() -> service.proposeAnalog(p.getId(), new AnalogCommand(p.getPlannedMaterial().getId(),
                new BigDecimal("100.00"), null, null, null, null)), PositionApprovalService.ERROR_SAME_AS_CURRENT);
    }

    // --- suggestions ---

    @Test
    @DisplayName("suggestion coexists with the open round; an analog with sourceSuggestionId resolves it")
    void suggestionResolvedByAnalog() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long open = send(p);
        Long suggestion = service.suggest(p.getId(),
                new SuggestCommand("https://shop.example.com/item/1", "this one"), clientDecider);
        PositionApprovalRoundEntity s = round(suggestion);
        assertThat(s.getKind()).isEqualTo(ApprovalRoundKind.CLIENT_SUGGESTION);
        assertThat(s.getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);
        assertThat(round(open).getStatus()).isEqualTo(ApprovalRoundStatus.OPEN);

        FinishingMaterialEntity analog = material(project);
        Long analogRound = service.proposeAnalog(p.getId(), new AnalogCommand(analog.getId(),
                new BigDecimal("92.00"), null, deadline(), null, suggestion));

        assertThat(round(suggestion).getStatus()).isEqualTo(ApprovalRoundStatus.RESOLVED);
        assertThat(round(analogRound).getSourceSuggestion().getId()).isEqualTo(suggestion);
    }

    @Test
    @DisplayName("declineSuggestion needs a reason, stores it and closes the suggestion as DECLINED")
    void suggestionDeclined() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        send(p);
        Long suggestion = service.suggest(p.getId(), new SuggestCommand("https://shop.example.com/x", null),
                clientDecider);
        assertThatThrownBy(() -> service.declineSuggestion(suggestion, "")).isInstanceOfSatisfying(
                ForemenApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        service.declineSuggestion(suggestion, "not available in Poland");
        PositionApprovalRoundEntity s = round(suggestion);
        assertThat(s.getStatus()).isEqualTo(ApprovalRoundStatus.DECLINED);
        assertThat(s.getManagerNote()).isEqualTo("not available in Poland");
        assertConflict(() -> service.declineSuggestion(suggestion, "again"), PositionApprovalService.ERROR_ROUND_NOT_OPEN);
    }

    @Test
    @DisplayName("suggestion with a non-http link -> 400")
    void suggestionInvalidLink() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        send(p);
        assertThatThrownBy(() -> service.suggest(p.getId(), new SuggestCommand("ftp://x.example.com", null),
                clientDecider)).isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- package choice ---

    @Test
    @DisplayName("package choice: price-less options, accept replaces the variant, position stays PLANNED")
    void packageChoiceAccept() {
        PurchasePositionEntity p = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        FinishingMaterialEntity a = material(project);
        FinishingMaterialEntity b = material(project);

        Long roundId = service.sendChoice(new SendChoiceCommand(p.getId(), List.of(a.getId(), b.getId()),
                deadline(), "pick one"));
        assertThat(round(roundId).getKind()).isEqualTo(ApprovalRoundKind.PACKAGE_CHOICE);
        List<PositionApprovalOptionEntity> options = optionDao.findByRoundIdOrderBySortOrderAscIdAsc(roundId);
        assertThat(options).hasSize(2);
        assertThat(options).allSatisfy(o -> assertThat(o.getUnitSalePrice()).isNull());
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.PLANNED);

        Long chosen = options.get(1).getId();
        assertThatThrownBy(() -> service.accept(roundId, new AcceptCommand(null, null, null), clientDecider))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        service.accept(roundId, new AcceptCommand(chosen, null, null), clientDecider);

        PositionApprovalRoundEntity closed = round(roundId);
        assertThat(closed.getStatus()).isEqualTo(ApprovalRoundStatus.ACCEPTED);
        assertThat(closed.getChosenOption().getId()).isEqualTo(chosen);
        PurchasePositionEntity after = reload(p);
        assertThat(after.getPlannedMaterial().getId()).isEqualTo(b.getId());
        assertThat(after.getStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(after.getDecisionDeadline()).isNull();
    }

    @Test
    @DisplayName("package choice with one option or a duplicate -> 400")
    void packageChoiceInvalidOptions() {
        PurchasePositionEntity p = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        FinishingMaterialEntity a = material(project);
        assertThatThrownBy(() -> service.sendChoice(new SendChoiceCommand(p.getId(), List.of(a.getId()),
                null, null))).isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.sendChoice(new SendChoiceCommand(p.getId(),
                List.of(a.getId(), a.getId()), null, null))).isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- client own ---

    @Test
    @DisplayName("client own from PROPOSED: open round superseded, DECLARED round, PLANNED + CLIENT_OWN path")
    void clientOwn() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long open = send(p);

        service.declareClientOwn(p.getId(), "I will buy it", clientDecider);

        assertThat(round(open).getStatus()).isEqualTo(ApprovalRoundStatus.SUPERSEDED);
        List<PositionApprovalRoundEntity> rounds = roundDao.findByPositionIds(List.of(p.getId()));
        assertThat(rounds).hasSize(2);
        PositionApprovalRoundEntity declared = rounds.get(1);
        assertThat(declared.getKind()).isEqualTo(ApprovalRoundKind.CLIENT_OWN);
        assertThat(declared.getStatus()).isEqualTo(ApprovalRoundStatus.DECLARED);
        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(after.getSourcingPath()).isEqualTo(SourcingPath.CLIENT_OWN);
        assertThat(after.getDecisionDeadline()).isNull();
    }

    @Test
    @DisplayName("client own from ACCEPTED goes through PROPOSED back to PLANNED")
    void clientOwnFromAccepted() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        service.accept(send(p), acceptAt("100.00"), clientDecider);
        em.flush();

        service.declareClientOwn(p.getId(), null, clientDecider);

        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(after.getSourcingPath()).isEqualTo(SourcingPath.CLIENT_OWN);
    }

    // --- withdraw / deadline ---

    @Test
    @DisplayName("withdraw of the first round restores PLANNED and clears the deadline; second withdraw is 409")
    void withdrawRestoresPlanned() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        service.withdraw(id);
        assertThat(round(id).getStatus()).isEqualTo(ApprovalRoundStatus.WITHDRAWN);
        PurchasePositionEntity after = reload(p);
        assertThat(after.getStatus()).isEqualTo(PositionStatus.PLANNED);
        assertThat(after.getDecisionDeadline()).isNull();
        assertConflict(() -> service.withdraw(id), PositionApprovalService.ERROR_ROUND_NOT_OPEN);
    }

    @Test
    @DisplayName("changeDeadline moves the round and position deadline; null removes it; a past date is rejected")
    void changeDeadline() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        LocalDate later = LocalDate.now().plusDays(20);

        service.changeDeadline(id, later);
        assertThat(round(id).getDecisionDeadline()).isEqualTo(later);
        assertThat(reload(p).getDecisionDeadline()).isEqualTo(later);

        service.changeDeadline(id, null);
        assertThat(round(id).getDecisionDeadline()).isNull();
        assertThat(reload(p).getDecisionDeadline()).isNull();

        assertThatThrownBy(() -> service.changeDeadline(id, LocalDate.now().minusDays(1)))
                .isInstanceOf(ForemenApiException.class);
    }

    // --- one open round / stage guard ---

    @Test
    @DisplayName("second send of an already proposed position is 409 notEligible and creates no round")
    void secondSendIsConflict() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        send(p);
        assertConflict(() -> service.send(new SendCommand(List.of(p.getId()), deadline(), null)),
                PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE);
        assertThat(roundDao.findByPositionIds(List.of(p.getId()))).hasSize(1);
    }

    @Test
    @DisplayName("racing OPEN round hits the partial unique index; client suggestions are exempt")
    void partialUniqueIndex() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        send(p);
        // A second OPEN non-suggestion round written behind the service's check (what a racing
        // transaction would do) must be rejected by uq_par_open_per_position.
        PositionApprovalRoundEntity dup = newOpenRound(p, 2, ApprovalRoundKind.ANALOG);
        assertThatThrownBy(() -> {
            em.persist(dup);
            em.flush();
        }).hasMessageContaining("uq_par_open_per_position");
    }

    @Test
    @DisplayName("sending a batch where one position is not eligible is all-or-nothing (409)")
    void sendIsAllOrNothing() {
        PurchasePositionEntity ok = agreed(PositionStatus.PLANNED);
        PurchasePositionEntity bad = agreed(PositionStatus.IN_PROFORMA);
        assertConflict(() -> service.send(new SendCommand(List.of(ok.getId(), bad.getId()), deadline(), null)),
                PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE);
        assertThat(roundDao.findByPositionIds(List.of(ok.getId()))).isEmpty();
        assertThat(reload(ok).getStatus()).isEqualTo(PositionStatus.PLANNED);
    }

    @Test
    @DisplayName("from IN_PROFORMA every operation is refused with 409 and nothing changes")
    void stageGuardInProforma() {
        PurchasePositionEntity p = agreed(PositionStatus.IN_PROFORMA);
        FinishingMaterialEntity analog = material(project);

        assertConflict(() -> service.send(new SendCommand(List.of(p.getId()), deadline(), null)),
                PositionApprovalService.ERROR_SEND_NOT_ELIGIBLE);
        assertConflict(() -> service.proposeAnalog(p.getId(), new AnalogCommand(analog.getId(),
                new BigDecimal("90.00"), null, null, null, null)), PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);
        assertConflict(() -> service.declareClientOwn(p.getId(), null, clientDecider),
                PositionApprovalService.ERROR_CLIENT_OWN_NOT_ALLOWED);
        assertConflict(() -> service.suggest(p.getId(), new SuggestCommand("https://a.example.com/x", null),
                clientDecider), PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);
        assertThat(roundDao.findByPositionIds(List.of(p.getId()))).isEmpty();
        assertThat(reload(p).getStatus()).isEqualTo(PositionStatus.IN_PROFORMA);
    }

    @Test
    @DisplayName("accept of a round whose position already left PROPOSED (IN_PROFORMA) is 409 stage")
    void acceptAfterStageMoved() {
        PurchasePositionEntity p = agreed(PositionStatus.PLANNED);
        Long id = send(p);
        em.createNativeQuery("UPDATE purchase_positions SET status = 'IN_PROFORMA' WHERE id = :id")
                .setParameter("id", p.getId()).executeUpdate();
        em.clear();
        assertConflict(() -> service.accept(id, acceptAt("100.00"), clientDecider),
                PositionApprovalService.ERROR_STAGE_NOT_ALLOWED);
    }

    private PositionApprovalRoundEntity newOpenRound(PurchasePositionEntity p, int no, ApprovalRoundKind kind) {
        PositionApprovalRoundEntity r = new PositionApprovalRoundEntity();
        r.setPosition(p);
        r.setRoundNo(no);
        r.setKind(kind);
        r.setStatus(ApprovalRoundStatus.OPEN);
        return r;
    }
}
