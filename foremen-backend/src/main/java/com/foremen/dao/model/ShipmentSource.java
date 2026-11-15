package com.foremen.dao.model;

/** FOR-11-04: who produced a shipment status or event ({@code SYSTEM} is used for events only). */
public enum ShipmentSource {
    TRACKING,
    MANUAL,
    SYSTEM
}
