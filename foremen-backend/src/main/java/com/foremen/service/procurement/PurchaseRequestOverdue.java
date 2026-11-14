package com.foremen.service.procurement;

import com.foremen.dao.model.RequestStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** FOR-11-00 (R11.9): overdue when expectedDeliveryDate is before today (Europe/Warsaw) and not COMPLETED/CANCELLED. */
public final class PurchaseRequestOverdue {

    public static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final Clock clock;

    public PurchaseRequestOverdue(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public boolean isOverdue(LocalDate expectedDeliveryDate, RequestStatus status) {
        if (expectedDeliveryDate == null || status == RequestStatus.COMPLETED || status == RequestStatus.CANCELLED) {
            return false;
        }
        return expectedDeliveryDate.isBefore(today());
    }
}
