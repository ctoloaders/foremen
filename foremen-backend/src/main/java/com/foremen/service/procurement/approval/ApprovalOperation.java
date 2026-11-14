package com.foremen.service.procurement.approval;

/** FOR-11-02 (R14.4): operations checked by {@link ApprovalStageGuard}. */
public enum ApprovalOperation {
    SEND,
    SEND_CHOICE,
    PROPOSE_ANALOG,
    SUGGEST,
    CLIENT_OWN,
    ACCEPT,
    REJECT
}
