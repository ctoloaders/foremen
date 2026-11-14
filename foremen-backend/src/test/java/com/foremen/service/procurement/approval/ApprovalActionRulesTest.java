package com.foremen.service.procurement.approval;

import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import static com.foremen.dao.model.PositionStatus.*;
import static com.foremen.service.procurement.approval.ApprovalRoundKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/** <b>Validates: Requirements 4.7, 14.4</b> */
class ApprovalActionRulesTest {

    private static Set<ClientAction> client(SourcingPath p, PositionStatus s, ApprovalRoundKind k) {
        return ApprovalActionRules.clientActions(Audience.CLIENT, p, s, k);
    }

    private static Set<StaffAction> staff(String role, SourcingPath p, PositionStatus s,
                                          ApprovalRoundKind k, int sugg) {
        return ApprovalActionRules.staffActions(Audience.STAFF_FULL, role, p, s, k, sugg);
    }

    @Test
    void clientWithOpenProposalCanDecideOwnOrSuggest() {
        assertThat(client(SourcingPath.AGREED, PROPOSED, PROPOSAL)).containsExactlyInAnyOrder(
                ClientAction.ACCEPT, ClientAction.REJECT, ClientAction.CLIENT_OWN, ClientAction.SUGGEST_LINK);
    }

    @Test
    void clientAcceptedPositionCanOnlyGoOwnOrSuggest() {
        assertThat(client(SourcingPath.AGREED, ACCEPTED, null))
                .containsExactlyInAnyOrder(ClientAction.CLIENT_OWN, ClientAction.SUGGEST_LINK);
    }

    @Test
    void clientPackageChoiceCanOnlyDecide() {
        assertThat(client(SourcingPath.PACKAGE, PLANNED, PACKAGE_CHOICE))
                .containsExactlyInAnyOrder(ClientAction.ACCEPT, ClientAction.REJECT);
    }

    @Test
    void clientGetsNothingAfterProforma() {
        assertThat(client(SourcingPath.AGREED, IN_PROFORMA, PROPOSAL)).isEmpty();
    }

    @Test
    void clientSuggestionRoundIsNotADecisionRound() {
        assertThat(client(SourcingPath.AGREED, PROPOSED, CLIENT_SUGGESTION))
                .containsExactlyInAnyOrder(ClientAction.CLIENT_OWN, ClientAction.SUGGEST_LINK);
    }

    @Test
    void nonClientAudiencesGetNoClientActions() {
        assertThat(ApprovalActionRules.clientActions(Audience.STAFF_FULL, SourcingPath.AGREED, PROPOSED, PROPOSAL))
                .isEmpty();
        assertThat(ApprovalActionRules.clientActions(Audience.SITE, SourcingPath.AGREED, PROPOSED, PROPOSAL))
                .isEmpty();
    }

    @Test
    void managerOnPlannedAgreedCanSend() {
        assertThat(staff("MANAGER", SourcingPath.AGREED, PLANNED, null, 0)).containsExactly(StaffAction.SEND);
    }

    @Test
    void managerOnPlannedPackageCanSendChoice() {
        assertThat(staff("ADMIN", SourcingPath.PACKAGE, PLANNED, null, 0))
                .containsExactly(StaffAction.SEND_CHOICE);
    }

    @Test
    void managerWithOpenProposalCanWithdrawMoveDeadlineDecideAndPropose() {
        assertThat(staff("MANAGER", SourcingPath.AGREED, PROPOSED, PROPOSAL, 0)).containsExactlyInAnyOrder(
                StaffAction.PROPOSE_ANALOG, StaffAction.WITHDRAW, StaffAction.CHANGE_DEADLINE,
                StaffAction.DECIDE_ON_BEHALF);
    }

    @Test
    void managerOnAcceptedCanProposeAnalogAndRecordClientOwn() {
        assertThat(staff("MANAGER", SourcingPath.AGREED, ACCEPTED, null, 0))
                .containsExactlyInAnyOrder(StaffAction.PROPOSE_ANALOG, StaffAction.DECIDE_ON_BEHALF);
    }

    @Test
    void openSuggestionsEnableResolveSuggestion() {
        assertThat(staff("MANAGER", SourcingPath.AGREED, PROPOSED, PROPOSAL, 2))
                .contains(StaffAction.RESOLVE_SUGGESTION);
        assertThat(staff("MANAGER", SourcingPath.AGREED, PROPOSED, PROPOSAL, 0))
                .doesNotContain(StaffAction.RESOLVE_SUGGESTION);
    }

    @Test
    void buyerAndOtherRolesGetNoStaffActions() {
        assertThat(staff("BUYER", SourcingPath.AGREED, PLANNED, null, 1)).isEmpty();
        assertThat(staff("FOREMAN", SourcingPath.AGREED, PLANNED, null, 1)).isEmpty();
        assertThat(staff(null, SourcingPath.AGREED, PLANNED, null, 1)).isEmpty();
    }

    @Test
    void clientAudienceGetsNoStaffActions() {
        assertThat(ApprovalActionRules.staffActions(Audience.CLIENT, "MANAGER", SourcingPath.AGREED, PLANNED,
                null, 0)).isEmpty();
    }

    @Test
    void staffGetsNothingAfterProformaExceptSuggestionResolution() {
        assertThat(staff("MANAGER", SourcingPath.AGREED, IN_PROFORMA, null, 0)).isEmpty();
    }

    /** Property: every offered action is backed by an OK stage guard result (single source of eligibility). */
    @Property
    void everyActionIsBackedByTheStageGuard(@ForAll SourcingPath path, @ForAll PositionStatus status,
                                            @ForAll ApprovalRoundKind kind) {
        Set<ClientAction> c = client(path, status, kind);
        if (c.contains(ClientAction.ACCEPT)) {
            assertThat(ApprovalStageGuard.check(path, status, ApprovalOperation.ACCEPT))
                    .isEqualTo(ApprovalStageGuard.Result.OK);
        }
        if (c.contains(ClientAction.CLIENT_OWN)) {
            assertThat(ApprovalStageGuard.check(path, status, ApprovalOperation.CLIENT_OWN))
                    .isEqualTo(ApprovalStageGuard.Result.OK);
        }
        Set<StaffAction> s = staff("MANAGER", path, status, kind, 0);
        if (s.contains(StaffAction.SEND)) {
            assertThat(ApprovalStageGuard.check(path, status, ApprovalOperation.SEND))
                    .isEqualTo(ApprovalStageGuard.Result.OK);
        }
        if (s.contains(StaffAction.PROPOSE_ANALOG)) {
            assertThat(ApprovalStageGuard.check(path, status, ApprovalOperation.PROPOSE_ANALOG))
                    .isEqualTo(ApprovalStageGuard.Result.OK);
        }
        if (status != PLANNED && status != PROPOSED && status != ACCEPTED) {
            assertThat(c).isEmpty();
            assertThat(s).doesNotContain(StaffAction.SEND, StaffAction.SEND_CHOICE,
                    StaffAction.PROPOSE_ANALOG, StaffAction.DECIDE_ON_BEHALF);
        }
    }
}
