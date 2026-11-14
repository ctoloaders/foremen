package com.foremen.service.procurement.approval;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.foremen.service.procurement.approval.ApprovalRoundStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** <b>Validates: Requirements 2.3, 2.4</b> */
class ApprovalRoundStatusMachineTest {

    private static final Set<ApprovalRoundKind> DECISION_KINDS =
            EnumSet.of(ApprovalRoundKind.PROPOSAL, ApprovalRoundKind.ANALOG, ApprovalRoundKind.PACKAGE_CHOICE);

    @Test
    void everyPairMatchesTheTable() {
        Set<ApprovalRoundStatus> decisionExits = EnumSet.of(ACCEPTED, REJECTED, SUPERSEDED, WITHDRAWN);
        Set<ApprovalRoundStatus> suggestionExits = EnumSet.of(RESOLVED, DECLINED);
        for (ApprovalRoundKind kind : ApprovalRoundKind.values()) {
            for (ApprovalRoundStatus from : ApprovalRoundStatus.values()) {
                for (ApprovalRoundStatus to : ApprovalRoundStatus.values()) {
                    boolean expected = from == OPEN
                            && ((DECISION_KINDS.contains(kind) && decisionExits.contains(to))
                            || (kind == ApprovalRoundKind.CLIENT_SUGGESTION && suggestionExits.contains(to)));
                    assertThat(ApprovalRoundStatusMachine.canMove(kind, from, to))
                            .as("%s %s -> %s", kind, from, to).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void clientOwnHasNoExitAndNullsAreRejected() {
        assertThat(ApprovalRoundStatusMachine.exits(ApprovalRoundKind.CLIENT_OWN, DECLARED)).isEmpty();
        assertThat(ApprovalRoundStatusMachine.exits(ApprovalRoundKind.CLIENT_OWN, OPEN)).isEmpty();
        assertThat(ApprovalRoundStatusMachine.canMove(null, OPEN, ACCEPTED)).isFalse();
        assertThat(ApprovalRoundStatusMachine.canMove(ApprovalRoundKind.PROPOSAL, OPEN, null)).isFalse();
    }

    @Test
    void exitsOfOpenRounds() {
        assertThat(ApprovalRoundStatusMachine.exits(ApprovalRoundKind.ANALOG, OPEN))
                .containsExactlyInAnyOrder(ACCEPTED, REJECTED, SUPERSEDED, WITHDRAWN);
        assertThat(ApprovalRoundStatusMachine.exits(ApprovalRoundKind.CLIENT_SUGGESTION, OPEN))
                .containsExactlyInAnyOrder(RESOLVED, DECLINED);
    }
}
