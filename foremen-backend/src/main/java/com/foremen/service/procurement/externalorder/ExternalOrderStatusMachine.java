package com.foremen.service.procurement.externalorder;

import static com.foremen.service.procurement.externalorder.ExternalOrderStatus.CANCELLED;
import static com.foremen.service.procurement.externalorder.ExternalOrderStatus.DRAFT;
import static com.foremen.service.procurement.externalorder.ExternalOrderStatus.PAID;
import static com.foremen.service.procurement.externalorder.ExternalOrderStatus.SENT_FOR_PAYMENT;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;

/**
 * FOR-11-03 (R11.4): pure status machine of an external order.
 *
 * <pre>
 * DRAFT -> SENT_FOR_PAYMENT | CANCELLED
 * SENT_FOR_PAYMENT -> PAID | DRAFT (return) | CANCELLED
 * PAID -> SENT_FOR_PAYMENT (only by R15.5 unmark / R16.5 ledger evidence loss)
 * CANCELLED terminal
 * </pre>
 *
 * Guards that depend on data (paid source, links, positions in delivery) live in the services.
 */
public final class ExternalOrderStatusMachine {

    public static final String NOT_ALLOWED = "error.externalOrder.status.notAllowed";

    private static final Map<ExternalOrderStatus, Set<ExternalOrderStatus>> EDGES =
            new EnumMap<>(ExternalOrderStatus.class);

    static {
        EDGES.put(DRAFT, EnumSet.of(SENT_FOR_PAYMENT, CANCELLED));
        EDGES.put(SENT_FOR_PAYMENT, EnumSet.of(PAID, DRAFT, CANCELLED));
        EDGES.put(PAID, EnumSet.of(SENT_FOR_PAYMENT));
        EDGES.put(CANCELLED, EnumSet.noneOf(ExternalOrderStatus.class));
    }

    private ExternalOrderStatusMachine() {
    }

    public static boolean canTransition(ExternalOrderStatus from, ExternalOrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return EDGES.get(from).contains(to);
    }

    /** @throws ForemenApiException 409 {@value #NOT_ALLOWED} for an illegal change */
    public static void requireTransition(ExternalOrderStatus from, ExternalOrderStatus to) {
        if (!canTransition(from, to)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_ALLOWED);
        }
    }
}
