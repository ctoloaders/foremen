package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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

    /** FOR-11-04 R6.2: the new SYSTEM-only edge IN_DELIVERY -> SUPPLIER_PAID. */
    @Test
    void shipmentReversalEdgeIsSystemOnlyOnAgreedAndPackage() {
        for (SourcingPath p : new SourcingPath[] {SourcingPath.AGREED, SourcingPath.PACKAGE}) {
            assertThat(d(p, IN_DELIVERY, SUPPLIER_PAID, Actor.SYSTEM)).isEqualTo(Decision.ALLOWED);
            assertThat(d(p, IN_DELIVERY, SUPPLIER_PAID, Actor.MANUAL)).isEqualTo(Decision.NOT_ALLOWED);
        }
        assertThat(d(SourcingPath.CLIENT_OWN, IN_DELIVERY, SUPPLIER_PAID, Actor.SYSTEM))
                .isEqualTo(Decision.NOT_ALLOWED);
    }

    /** FOR-11-04 R26.2: full edge matrix, the new edge is the only change versus FOR-11-03. */
    @Test
    void fullEdgeMatrixNothingElseChanged() {
        for (SourcingPath p : SourcingPath.values()) {
            Set<String> system = new HashSet<>();
            Set<String> manual = new HashSet<>();
            if (p == SourcingPath.AGREED || p == SourcingPath.PACKAGE) {
                system.addAll(Set.of("WITH_BUYER>ORDERED", "ORDERED>SUPPLIER_PAID", "SUPPLIER_PAID>IN_DELIVERY",
                        "ORDERED>WITH_BUYER", "SUPPLIER_PAID>ORDERED", "IN_DELIVERY>SUPPLIER_PAID"));
            }
            system.addAll(Set.of("IN_DELIVERY>PARTIALLY_RECEIVED", "PARTIALLY_RECEIVED>IN_DELIVERY",
                    "IN_DELIVERY>RECEIVED", "PARTIALLY_RECEIVED>RECEIVED", "RECEIVED>PARTIALLY_RECEIVED"));
            for (PositionStatus s : PositionStatus.values()) {
                if (s != RECEIVED && s != CANCELLED) {
                    manual.add(s + ">CANCELLED");
                }
            }
            manual.add("CANCELLED>PLANNED");
            if (p == SourcingPath.AGREED) {
                system.addAll(Set.of("PLANNED>PROPOSED", "PROPOSED>PLANNED", "PROPOSED>ACCEPTED",
                        "ACCEPTED>PROPOSED", "ACCEPTED>IN_PROFORMA", "IN_PROFORMA>ACCEPTED",
                        "IN_PROFORMA>CLIENT_PAID", "CLIENT_PAID>WITH_BUYER"));
            }
            if (p == SourcingPath.PACKAGE) {
                manual.add("PLANNED>WITH_BUYER");
            }
            if (p == SourcingPath.CLIENT_OWN) {
                manual.add("PLANNED>IN_DELIVERY");
            }
            for (PositionStatus from : PositionStatus.values()) {
                for (PositionStatus to : PositionStatus.values()) {
                    String edge = from + ">" + to;
                    for (Actor a : Actor.values()) {
                        Decision expected;
                        if (from == to) {
                            expected = Decision.NOT_ALLOWED;
                        } else if (p == SourcingPath.AGREED && to == WITH_BUYER
                                && from != CLIENT_PAID && from != ORDERED) {
                            expected = Decision.GATE_VIOLATION;
                        } else if (manual.contains(edge) || (system.contains(edge) && a == Actor.SYSTEM)) {
                            expected = Decision.ALLOWED;
                        } else {
                            expected = Decision.NOT_ALLOWED;
                        }
                        assertThat(d(p, from, to, a)).as("%s %s %s", p, edge, a).isEqualTo(expected);
                    }
                }
            }
        }
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
