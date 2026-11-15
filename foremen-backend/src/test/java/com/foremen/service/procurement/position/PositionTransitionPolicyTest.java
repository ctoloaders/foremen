package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;
import org.junit.jupiter.api.Test;

import static com.foremen.dao.model.PositionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** FOR-11-00 R8.1-8.5. */
class PositionTransitionPolicyTest {

    private final PositionTransitionPolicy policy = new PositionTransitionPolicy();

    private Decision d(SourcingPath p, PositionStatus f, PositionStatus t, Actor a) {
        return policy.decide(p, f, t, a);
    }

    @Test
    void agreedForwardChainIsSystemOnly() {
        PositionStatus[] chain = {PLANNED, PROPOSED, ACCEPTED, IN_PROFORMA, CLIENT_PAID, WITH_BUYER, ORDERED,
                SUPPLIER_PAID, IN_DELIVERY, PARTIALLY_RECEIVED, RECEIVED};
        for (int i = 0; i < chain.length - 1; i++) {
            assertThat(d(SourcingPath.AGREED, chain[i], chain[i + 1], Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
            assertThat(d(SourcingPath.AGREED, chain[i], chain[i + 1], Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
        }
    }

    @Test
    void agreedBackEdges() {
        assertThat(d(SourcingPath.AGREED, PROPOSED, PLANNED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.AGREED, ACCEPTED, PROPOSED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.AGREED, IN_PROFORMA, ACCEPTED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.AGREED, PARTIALLY_RECEIVED, IN_DELIVERY, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.AGREED, RECEIVED, PARTIALLY_RECEIVED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void gateBlocksSkippingProformaPayment() {
        for (PositionStatus from : new PositionStatus[] {PLANNED, PROPOSED, ACCEPTED, IN_PROFORMA}) {
            assertThat(d(SourcingPath.AGREED, from, WITH_BUYER, Actor.SYSTEM)).isEqualTo(Decision.GATE_VIOLATION);
            assertThat(d(SourcingPath.AGREED, from, WITH_BUYER, Actor.MANUAL)).isEqualTo(Decision.GATE_VIOLATION);
        }
        assertThat(d(SourcingPath.AGREED, CLIENT_PAID, WITH_BUYER, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.AGREED, CLIENT_PAID, WITH_BUYER, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
    }

    /** FOR-11-03 R17.4: SYSTEM-only reversal edges, no other edge changes. */
    @Test
    void orderReversalEdgesAreSystemOnly() {
        for (SourcingPath p : new SourcingPath[] {SourcingPath.AGREED, SourcingPath.PACKAGE}) {
            assertThat(d(p, ORDERED, WITH_BUYER, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, ORDERED, WITH_BUYER, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
            assertThat(d(p, SUPPLIER_PAID, ORDERED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, SUPPLIER_PAID, ORDERED, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
            // reversal is one step at a time
            assertThat(d(p, SUPPLIER_PAID, WITH_BUYER, Actor.SYSTEM)).isNotEqualTo(Decision.ALLOWED);
        }
        assertThat(d(SourcingPath.CLIENT_OWN, ORDERED, WITH_BUYER, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
        assertThat(d(SourcingPath.CLIENT_OWN, SUPPLIER_PAID, ORDERED, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
        // the gate still blocks everything else entering WITH_BUYER on AGREED
        assertThat(d(SourcingPath.AGREED, SUPPLIER_PAID, WITH_BUYER, Actor.SYSTEM))
                .isEqualTo(Decision.GATE_VIOLATION);
    }

    @Test
    void packageAndClientOwnManualShortcuts() {
        assertThat(d(SourcingPath.PACKAGE, PLANNED, WITH_BUYER, Actor.MANUAL)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.CLIENT_OWN, PLANNED, IN_DELIVERY, Actor.MANUAL)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.CLIENT_OWN, PLANNED, WITH_BUYER, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
        assertThat(d(SourcingPath.PACKAGE, PLANNED, IN_DELIVERY, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
        assertThat(d(SourcingPath.PACKAGE, PLANNED, PROPOSED, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
        assertThat(d(SourcingPath.PACKAGE, WITH_BUYER, ORDERED, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.CLIENT_OWN, IN_DELIVERY, PARTIALLY_RECEIVED, Actor.SYSTEM))
                .isEqualTo(Decision.ALLOWED);
        assertThat(d(SourcingPath.CLIENT_OWN, WITH_BUYER, ORDERED, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
    }

    @Test
    void cancelAndReopen() {
        for (SourcingPath p : SourcingPath.values()) {
            assertThat(d(p, PLANNED, CANCELLED, Actor.MANUAL)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, IN_DELIVERY, CANCELLED, Actor.MANUAL)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, RECEIVED, CANCELLED, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
            assertThat(d(p, CANCELLED, PLANNED, Actor.MANUAL)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, CANCELLED, ORDERED, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
        }
    }

    @Test
    void sameStageIsNotAllowed() {
        for (PositionStatus s : PositionStatus.values()) {
            assertThat(d(SourcingPath.AGREED, s, s, Actor.SYSTEM)).isEqualTo(Decision.NOT_ALLOWED);
        }
    }
}
