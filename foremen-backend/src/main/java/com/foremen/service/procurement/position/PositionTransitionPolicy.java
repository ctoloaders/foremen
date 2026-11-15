package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.foremen.dao.model.PositionStatus.*;

/**
 * FOR-11-00 (R8.1-8.5): pure decision on whether {@code (path, from, to, actor)} is a legal position
 * transition. Holds the "proforma paid" gate: for {@code AGREED}, {@code WITH_BUYER} is entered only
 * from {@code CLIENT_PAID}, which only {@code SYSTEM} reaches from {@code IN_PROFORMA}.
 *
 * <p>FOR-11-03 (R17.4) adds the SYSTEM reversal edges {@code ORDERED -> WITH_BUYER} and
 * {@code SUPPLIER_PAID -> ORDERED}. The gate does not apply to {@code ORDERED -> WITH_BUYER}: the
 * position already passed the gate, this is a step back.
 *
 * <pre>
 * AGREED:  PLANNED <-> PROPOSED <-> ACCEPTED <-> IN_PROFORMA -> CLIENT_PAID -> WITH_BUYER
 * tail (AGREED, PACKAGE):
 *          WITH_BUYER <-> ORDERED <-> SUPPLIER_PAID -> IN_DELIVERY
 *          (ORDERED -> WITH_BUYER and SUPPLIER_PAID -> ORDERED: SYSTEM reversals, FOR-11-03)
 * all paths: IN_DELIVERY <-> PARTIALLY_RECEIVED, IN_DELIVERY / PARTIALLY_RECEIVED <-> RECEIVED
 * </pre>
 *
 * <p>Rule order: (1) same stage, NOT_ALLOWED; (2) the gate, GATE_VIOLATION (checked before the edge
 * lookup, otherwise a skipped edge would be reported as a plain NOT_ALLOWED); (3) the edge must exist
 * for the path; (4) {@code MANUAL} is limited to the manual edges, the rest need {@code SYSTEM}.
 */
@Component
public class PositionTransitionPolicy {

    public enum Actor { MANUAL, SYSTEM }

    public enum Decision { ALLOWED, NOT_ALLOWED, GATE_VIOLATION }

    private static final Map<SourcingPath, Map<PositionStatus, Set<PositionStatus>>> SYSTEM_EDGES =
            new EnumMap<>(SourcingPath.class);
    private static final Map<SourcingPath, Map<PositionStatus, Set<PositionStatus>>> MANUAL_EDGES =
            new EnumMap<>(SourcingPath.class);

    static {
        for (SourcingPath p : SourcingPath.values()) {
            SYSTEM_EDGES.put(p, new EnumMap<>(PositionStatus.class));
            MANUAL_EDGES.put(p, new EnumMap<>(PositionStatus.class));
        }
        // Tail shared by AGREED and PACKAGE.
        for (SourcingPath p : new SourcingPath[] {SourcingPath.AGREED, SourcingPath.PACKAGE}) {
            system(p, WITH_BUYER, ORDERED);
            system(p, ORDERED, SUPPLIER_PAID);
            system(p, SUPPLIER_PAID, IN_DELIVERY);
            // FOR-11-03 (R17.4): reversals when order coverage or payment evidence disappears.
            system(p, ORDERED, WITH_BUYER);
            system(p, SUPPLIER_PAID, ORDERED);
        }
        // Receiving chain, every path.
        for (SourcingPath p : SourcingPath.values()) {
            system(p, IN_DELIVERY, PARTIALLY_RECEIVED);
            system(p, PARTIALLY_RECEIVED, IN_DELIVERY);
            system(p, IN_DELIVERY, RECEIVED);
            system(p, PARTIALLY_RECEIVED, RECEIVED);
            system(p, RECEIVED, PARTIALLY_RECEIVED);
            // Cancel from any stage before RECEIVED, and reopen.
            for (PositionStatus s : PositionStatus.values()) {
                if (s != RECEIVED && s != CANCELLED) {
                    manual(p, s, CANCELLED);
                }
            }
            manual(p, CANCELLED, PLANNED);
        }
        // AGREED negotiation / proforma / payment gate.
        system(SourcingPath.AGREED, PLANNED, PROPOSED);
        system(SourcingPath.AGREED, PROPOSED, PLANNED);
        system(SourcingPath.AGREED, PROPOSED, ACCEPTED);
        system(SourcingPath.AGREED, ACCEPTED, PROPOSED);
        system(SourcingPath.AGREED, ACCEPTED, IN_PROFORMA);
        system(SourcingPath.AGREED, IN_PROFORMA, ACCEPTED);
        system(SourcingPath.AGREED, IN_PROFORMA, CLIENT_PAID);
        system(SourcingPath.AGREED, CLIENT_PAID, WITH_BUYER);
        // Manual shortcuts.
        manual(SourcingPath.PACKAGE, PLANNED, WITH_BUYER);
        manual(SourcingPath.CLIENT_OWN, PLANNED, IN_DELIVERY);
    }

    private static void system(SourcingPath p, PositionStatus from, PositionStatus to) {
        SYSTEM_EDGES.get(p).computeIfAbsent(from, k -> EnumSet.noneOf(PositionStatus.class)).add(to);
    }

    private static void manual(SourcingPath p, PositionStatus from, PositionStatus to) {
        MANUAL_EDGES.get(p).computeIfAbsent(from, k -> EnumSet.noneOf(PositionStatus.class)).add(to);
    }

    public Decision decide(SourcingPath path, PositionStatus from, PositionStatus to, Actor actor) {
        if (path == null || from == null || to == null || actor == null || from == to) {
            return Decision.NOT_ALLOWED;
        }
        if (to == WITH_BUYER && path == SourcingPath.AGREED
                && from != CLIENT_PAID && from != ORDERED) {
            return Decision.GATE_VIOLATION;
        }
        if (has(MANUAL_EDGES, path, from, to)) {
            return Decision.ALLOWED; // manual edges may also be driven by the system
        }
        if (has(SYSTEM_EDGES, path, from, to)) {
            return actor == Actor.SYSTEM ? Decision.ALLOWED : Decision.NOT_ALLOWED;
        }
        return Decision.NOT_ALLOWED;
    }

    private static boolean has(Map<SourcingPath, Map<PositionStatus, Set<PositionStatus>>> edges,
                               SourcingPath path, PositionStatus from, PositionStatus to) {
        return edges.get(path).getOrDefault(from, Set.of()).contains(to);
    }
}
