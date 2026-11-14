package com.foremen.service.procurement.approval;

import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-02 (R5.2, R14.2): list filters, all applied in the database query. {@code overdue} and
 * {@code sourcingPath} are used by the staff view; the client endpoint passes {@code false} / {@code null}.
 */
public record ApprovalFilter(ClientStatus status, boolean onlyPending, Integer blockNo, boolean overdue,
                             SourcingPath sourcingPath) {

    public static ApprovalFilter none() {
        return new ApprovalFilter(null, false, null, false, null);
    }
}
