package com.foremen.service.estimateai;

/**
 * FOR-05-04 — codes of the AI report warnings (Requirements 3.2, 5.4, 5.5, 6.6, 21, 23.9) and the
 * reasons of {@link Unpriced} items (Requirements 5.3, 6.5, 9.3).
 */
public final class WarningCodes {

    private WarningCodes() {
    }

    // Plan validator (Requirement 5.5)
    public static final String NEGATIVE_VOLUME = "NEGATIVE_VOLUME";
    public static final String DUPLICATE_WORK_ROOM = "DUPLICATE_WORK_ROOM";
    public static final String ALTERNATIVES_BOTH_PRESENT = "ALTERNATIVES_BOTH_PRESENT";
    public static final String VOLUME_EXCEEDS_ROOM = "VOLUME_EXCEEDS_ROOM";
    public static final String ROOM_WITHOUT_WORKS = "ROOM_WITHOUT_WORKS";
    // Requirement 5.4
    public static final String WORK_ROOM_TYPE_MISMATCH = "WORK_ROOM_TYPE_MISMATCH";
    // Resolver
    public static final String UNKNOWN_ROOM = "UNKNOWN_ROOM";
    // Requirement 21 (cell explanation)
    public static final String RATIONALE_MISSING = "RATIONALE_MISSING";
    public static final String ADJUSTMENT_REASON_MISSING = "ADJUSTMENT_REASON_MISSING";
    // Requirements 6.6, 3.2, 23.9
    public static final String EDIT_REJECTED = "EDIT_REJECTED";
    public static final String ROOM_NOT_SAVED = "ROOM_NOT_SAVED";
    public static final String MATERIAL_PRICE_RANGE_MISSING = "MATERIAL_PRICE_RANGE_MISSING";

    // Requirement 8.8: the snapshot has no rows
    public static final String EMPTY_OFFER = "EMPTY_OFFER";

    // Unpriced reasons
    public static final String UNKNOWN_CODE = "UNKNOWN_CODE";
    public static final String NO_PRICE = "NO_PRICE";
    public static final String UNSUPPORTED_UNIT = "UNSUPPORTED_UNIT";
    public static final String NOT_IN_CATALOG = "NOT_IN_CATALOG";
}
