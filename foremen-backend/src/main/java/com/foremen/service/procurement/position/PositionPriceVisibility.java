package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

import java.util.EnumSet;
import java.util.Set;

/** FOR-11-00 (R10): pure policy deciding which Money_Fields an audience may see. */
public final class PositionPriceVisibility {

    public enum Audience { STAFF_FULL, SITE, CLIENT }

    /** Stages from {@code ACCEPTED} onwards that a client may see a sale price for. */
    private static final Set<PositionStatus> CLIENT_VISIBLE = EnumSet.of(
            PositionStatus.ACCEPTED, PositionStatus.IN_PROFORMA, PositionStatus.CLIENT_PAID,
            PositionStatus.WITH_BUYER, PositionStatus.ORDERED, PositionStatus.SUPPLIER_PAID,
            PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED);

    private PositionPriceVisibility() {
    }

    public static PositionMoney apply(Audience audience, SourcingPath path, PositionStatus status, PositionMoney raw) {
        if (audience == null || raw == null) {
            return PositionMoney.NONE;
        }
        return switch (audience) {
            case STAFF_FULL -> raw;
            case SITE -> PositionMoney.NONE;
            case CLIENT -> path == SourcingPath.AGREED && status != null && CLIENT_VISIBLE.contains(status)
                    ? new PositionMoney(null, null, null, raw.salePrice(), null, null)
                    : PositionMoney.NONE;
        };
    }
}
