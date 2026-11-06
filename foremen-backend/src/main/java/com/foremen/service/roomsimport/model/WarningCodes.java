package com.foremen.service.roomsimport.model;

import java.util.List;

/** Single source of truth for rooms-import warning codes (Requirements 3-9). */
public final class WarningCodes {

    private WarningCodes() {}

    // Detection (Requirement 3)
    public static final String ROOM_COUNT_MISMATCH = "ROOM_COUNT_MISMATCH";
    public static final String NO_ROOMS_DETECTED = "NO_ROOMS_DETECTED";
    public static final String DUPLICATE_ROOM_SUSPECTED = "DUPLICATE_ROOM_SUSPECTED";
    public static final String POSSIBLE_DUPLICATE_OF_EXISTING = "POSSIBLE_DUPLICATE_OF_EXISTING";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String ROOM_ANALYSIS_FAILED = "ROOM_ANALYSIS_FAILED";

    // Classification (Requirement 4)
    public static final String ROOM_TYPE_UNRESOLVED = "ROOM_TYPE_UNRESOLVED";

    // Measuring / geometry (Requirement 5)
    public static final String SCALE_UNKNOWN = "SCALE_UNKNOWN";
    public static final String OPENING_DROPPED = "OPENING_DROPPED";
    public static final String GEOMETRY_SYNTHESIZED = "GEOMETRY_SYNTHESIZED";
    public static final String DIMENSION_MISSING = "DIMENSION_MISSING";

    // Inputs (Requirement 6)
    public static final String FILE_UNREADABLE = "FILE_UNREADABLE";

    // Validation (Requirement 7)
    public static final String GEOMETRY_INVALID = "GEOMETRY_INVALID";
    public static final String AREA_MISMATCH = "AREA_MISMATCH";
    public static final String PERIMETER_MISMATCH = "PERIMETER_MISMATCH";
    public static final String OPENINGS_EXCEED_WALL = "OPENINGS_EXCEED_WALL";
    public static final String OPENING_TOO_WIDE = "OPENING_TOO_WIDE";
    public static final String CEILING_HEIGHT_OUT_OF_RANGE = "CEILING_HEIGHT_OUT_OF_RANGE";
    public static final String INTERNAL_CORNERS_MISMATCH = "INTERNAL_CORNERS_MISMATCH";
    public static final String DIMENSION_OUT_OF_RANGE = "DIMENSION_OUT_OF_RANGE";

    // Media (Requirement 9)
    public static final String CROP_UNAVAILABLE = "CROP_UNAVAILABLE";

    /** Every code above; the frontend {@code WARNING_CODES} must mirror this list. */
    public static final List<String> ALL = List.of(
            ROOM_COUNT_MISMATCH, NO_ROOMS_DETECTED, DUPLICATE_ROOM_SUSPECTED, POSSIBLE_DUPLICATE_OF_EXISTING,
            LOW_CONFIDENCE, ROOM_ANALYSIS_FAILED, ROOM_TYPE_UNRESOLVED, SCALE_UNKNOWN,
            OPENING_DROPPED, GEOMETRY_SYNTHESIZED, DIMENSION_MISSING, FILE_UNREADABLE,
            GEOMETRY_INVALID, AREA_MISMATCH, PERIMETER_MISMATCH, OPENINGS_EXCEED_WALL,
            OPENING_TOO_WIDE, CEILING_HEIGHT_OUT_OF_RANGE, INTERNAL_CORNERS_MISMATCH,
            DIMENSION_OUT_OF_RANGE, CROP_UNAVAILABLE);
}
