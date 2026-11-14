package com.foremen.service.procurement.approval;

/** FOR-11-02 (R2.3): status of an approval round, stored by name. */
public enum ApprovalRoundStatus {
    OPEN,
    ACCEPTED,
    REJECTED,
    SUPERSEDED,
    WITHDRAWN,
    DECLARED,
    RESOLVED,
    DECLINED
}
