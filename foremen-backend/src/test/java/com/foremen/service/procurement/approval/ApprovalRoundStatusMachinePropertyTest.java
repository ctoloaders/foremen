package com.foremen.service.procurement.approval;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import static com.foremen.service.procurement.approval.ApprovalRoundStatus.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

/** <b>Validates: Requirements 2.4</b> */
class ApprovalRoundStatusMachinePropertyTest {

    /** Every status other than OPEN is terminal: no exit for any kind. */
    @Property
    void terminalStatesHaveNoExit(@ForAll ApprovalRoundKind kind, @ForAll ApprovalRoundStatus from,
                                  @ForAll ApprovalRoundStatus to) {
        if (from != OPEN) {
            assertThat(ApprovalRoundStatusMachine.exits(kind, from)).isEmpty();
            assertThat(ApprovalRoundStatusMachine.isTerminal(kind, from)).isTrue();
            assertThat(ApprovalRoundStatusMachine.canMove(kind, from, to)).isFalse();
        }
    }
}
