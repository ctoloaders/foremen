package com.foremen.dao.model;

/** FOR-11-04 (R5.2): who owns the shipment ETA; a {@code MANUAL} ETA is never overwritten by the carrier. */
public enum EtaSource {
    TRACKING,
    MANUAL
}
