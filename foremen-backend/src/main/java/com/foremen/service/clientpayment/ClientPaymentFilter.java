package com.foremen.service.clientpayment;

import java.time.LocalDate;

/**
 * FOR-11-06 (R1.5, R2.2): filter of the combined client-payment list. Every component is optional.
 *
 * @param projectId project scope narrowing (still intersected with the caller's allowed projects)
 * @param kind      payment kind
 * @param status    display status
 * @param overdue   {@code true} only overdue, {@code false} only not overdue
 * @param dueFrom   inclusive lower bound of the due date
 * @param dueTo     inclusive upper bound of the due date
 * @param query     case-insensitive text matched against the proforma number and the note
 */
public record ClientPaymentFilter(Long projectId, ClientPaymentKind kind, DisplayStatus status, Boolean overdue,
                                  LocalDate dueFrom, LocalDate dueTo, String query) {

    public static ClientPaymentFilter none() {
        return new ClientPaymentFilter(null, null, null, null, null, null, null);
    }
}
