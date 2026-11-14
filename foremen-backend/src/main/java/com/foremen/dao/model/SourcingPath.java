package com.foremen.dao.model;

/** FOR-11-00 (R7.2): who sources the position; {@code AGREED} must pass the "proforma paid" gate. */
public enum SourcingPath {
    CLIENT_OWN,
    PACKAGE,
    AGREED
}
