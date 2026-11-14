package com.foremen.dao.model;

/** FOR-11-00 (R7.2): the kind of a purchase position, stored by name. Only {@code FINISHING} is supported now. */
public enum PositionKind {
    FINISHING,
    CONSTRUCTION,
    FURNITURE
}
