package com.foremen.service.roomsimport.validate;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.DraftRoom;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Read-only input of a validation rule for one draft room (FOR-05-02 design B5).
 *
 * @param tempId          draft room temp id (used as {@code roomTempId} of the warnings)
 * @param confidence      detection confidence in {@code [0,1]}
 * @param room            the ready-to-save create request, may be {@code null}
 * @param geometry        {@code room.geometry}, may be {@code null} (scalar-only room)
 * @param ceilingHeight   {@code room.ceilingHeight}, may be {@code null}
 * @param internalCorners {@code room.internalCorners} falling back to {@code stated.internalCorners}
 * @param stated          values read literally off the source, never {@code null}
 * @param calculated      unmodified output of the calculation engine, may be {@code null}
 * @param thresholds      configured thresholds
 */
public record ValidationContext(
        String tempId,
        double confidence,
        RoomCreateRequest room,
        RoomGeometry geometry,
        BigDecimal ceilingHeight,
        Integer internalCorners,
        Map<String, Object> stated,
        CalculatedMetrics calculated,
        RoomsImportProperties.Validation thresholds) {

    /** Builds the context of a draft room; never mutates the draft. */
    public static ValidationContext of(DraftRoom draft, RoomsImportProperties.Validation thresholds) {
        RoomCreateRequest room = draft.room();
        Map<String, Object> stated = draft.stated() == null ? Map.of() : draft.stated();
        Integer corners = room == null ? null : room.internalCorners();
        if (corners == null) {
            Double s = Num.toDouble(stated.get("internalCorners"));
            corners = s == null ? null : (int) Math.round(s);
        }
        return new ValidationContext(draft.tempId(), draft.confidence(), room,
                room == null ? null : room.geometry(),
                room == null ? null : room.ceilingHeight(),
                corners, stated, draft.calculated(), thresholds);
    }
}
