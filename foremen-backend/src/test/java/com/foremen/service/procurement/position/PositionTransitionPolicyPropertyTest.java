package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.EnumSet;
import java.util.Set;

import static com.foremen.dao.model.PositionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate properties for FOR-11-00.
 *
 * <p><b>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 8.8, 12.2, 12.5</b>
 */
class PositionTransitionPolicyPropertyTest {

    private static final Set<PositionStatus> GATED =
            EnumSet.of(PROPOSED, ACCEPTED, IN_PROFORMA, CLIENT_PAID, WITH_BUYER);

    private final PositionTransitionPolicy policy = new PositionTransitionPolicy();

    /** For every path/stage/actor, AGREED reaches WITH_BUYER only from CLIENT_PAID, and only by SYSTEM. */
    @Property
    void agreedEntersWithBuyerOnlyFromClientPaid(
            @ForAll SourcingPath path, @ForAll PositionStatus from, @ForAll PositionStatus to, @ForAll Actor actor) {
        Decision r = policy.decide(path, from, to, actor);
        if (path == SourcingPath.AGREED && to == WITH_BUYER && r == Decision.ALLOWED) {
            assertThat(from).isEqualTo(CLIENT_PAID);
            assertThat(actor).isEqualTo(Actor.SYSTEM);
        }
        if (path == SourcingPath.AGREED && to == WITH_BUYER && from != CLIENT_PAID && from != WITH_BUYER) {
            assertThat(r).isEqualTo(Decision.GATE_VIOLATION);
        }
    }

    /** MANUAL never enters the gated stages on AGREED. */
    @Property
    void manualNeverEntersGatedStagesOnAgreed(@ForAll PositionStatus from, @ForAll PositionStatus to) {
        if (GATED.contains(to)) {
            assertThat(policy.decide(SourcingPath.AGREED, from, to, Actor.MANUAL)).isNotEqualTo(Decision.ALLOWED);
        }
    }

    /** CLIENT_PAID is reachable only by SYSTEM from IN_PROFORMA, on every path. */
    @Property
    void clientPaidOnlyFromProformaBySystem(
            @ForAll SourcingPath path, @ForAll PositionStatus from, @ForAll Actor actor) {
        if (policy.decide(path, from, CLIENT_PAID, actor) == Decision.ALLOWED) {
            assertThat(path).isEqualTo(SourcingPath.AGREED);
            assertThat(from).isEqualTo(IN_PROFORMA);
            assertThat(actor).isEqualTo(Actor.SYSTEM);
        }
    }

    /** Only AGREED targeting WITH_BUYER can produce a gate violation. */
    @Property
    void gateViolationOnlyForAgreedWithBuyer(
            @ForAll SourcingPath path, @ForAll PositionStatus from, @ForAll PositionStatus to, @ForAll Actor actor) {
        if (policy.decide(path, from, to, actor) == Decision.GATE_VIOLATION) {
            assertThat(path).isEqualTo(SourcingPath.AGREED);
            assertThat(to).isEqualTo(WITH_BUYER);
            assertThat(from).isNotEqualTo(CLIENT_PAID);
        }
    }

    /** Stage mapping yields a code and a rank for every stage. */
    @Property
    void stageMappingTotal(@ForAll PositionStatus status) {
        assertThat(PositionStage.statusCode(status)).isNotNull().matches("[a-z_]+");
        if (status == CANCELLED) {
            assertThat(PositionStage.rank(status)).isEqualTo(PositionStage.UNRANKED);
        } else {
            assertThat(PositionStage.rank(status)).isBetween(1, 11);
        }
    }
}
