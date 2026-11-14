package com.foremen.service.procurement.position;

import java.util.EnumSet;
import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

/**
 * FOR-11-00 (R21.7): pure policy deciding at which stage a position's material option may be
 * replaced or restored. A MANUAL caller (REST) may do it only at {@code PLANNED} / {@code PROPOSED};
 * from {@code ACCEPTED} on the client must re-accept (FOR-11-02), so only the SYSTEM actor may do it
 * there. From {@code IN_PROFORMA} on nobody may.
 */
public final class VariantStagePolicy {

    private static final Set<PositionStatus> MANUAL_STAGES = EnumSet.of(
            PositionStatus.PLANNED, PositionStatus.PROPOSED);
    private static final Set<PositionStatus> SYSTEM_STAGES = EnumSet.of(
            PositionStatus.PLANNED, PositionStatus.PROPOSED, PositionStatus.ACCEPTED);

    private VariantStagePolicy() {
    }

    public static boolean allowed(PositionStatus status, Actor actor) {
        if (status == null || actor == null) {
            return false;
        }
        return (actor == Actor.SYSTEM ? SYSTEM_STAGES : MANUAL_STAGES).contains(status);
    }
}
