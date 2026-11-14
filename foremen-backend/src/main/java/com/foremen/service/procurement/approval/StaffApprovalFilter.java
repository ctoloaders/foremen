package com.foremen.service.procurement.approval;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import java.util.Set;

/** FOR-11-02 (design 5): staff list filters, applied in the query. {@code null}/empty means "no filter". */
public record StaffApprovalFilter(Set<PositionStatus> statuses, boolean overdue, Integer blockNo,
                                  SourcingPath sourcingPath) {
    public static StaffApprovalFilter none() {
        return new StaffApprovalFilter(null, false, null, null);
    }
}
