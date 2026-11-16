package com.foremen.service.procurement.position;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;

/** FOR-11-06 R10.2: the one flag-guarded edge; the proforma-paid gate of {@code decide} is unchanged. */
class PositionTransitionPolicyBuyerAddedTest {

    private final PositionTransitionPolicy policy = new PositionTransitionPolicy();

    @Test
    void buyerAddedAllowsOnlyPlannedToWithBuyer() {
        for (PositionStatus from : PositionStatus.values()) {
            for (PositionStatus to : PositionStatus.values()) {
                Decision expected = from == PositionStatus.PLANNED && to == PositionStatus.WITH_BUYER
                        ? Decision.ALLOWED : Decision.NOT_ALLOWED;
                assertThat(policy.decideBuyerAdded(from, to)).as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
    }

    @Test
    void genericDecideStillHitsTheGateForAnUnflaggedPosition() {
        for (Actor actor : Actor.values()) {
            assertThat(policy.decide(SourcingPath.AGREED, PositionStatus.PLANNED, PositionStatus.WITH_BUYER, actor))
                    .isEqualTo(Decision.GATE_VIOLATION);
        }
    }

    @Test
    void nullInputsAreNotAllowed() {
        assertThat(policy.decideBuyerAdded(null, PositionStatus.WITH_BUYER)).isEqualTo(Decision.NOT_ALLOWED);
        assertThat(policy.decideBuyerAdded(PositionStatus.PLANNED, null)).isEqualTo(Decision.NOT_ALLOWED);
    }
}
