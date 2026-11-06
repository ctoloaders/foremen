package com.foremen.service.model;

import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.RoomGeometry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-path service model for a room, carrying flat metric values, their source flags, the derived
 * counts/gaps, and the geometry.
 *
 * <p>It is mutable ({@code @Data}) so the {@code RoomService} pre-persist normalization step can
 * overwrite the five derived metrics + stamp their sources ({@code CALCULATED} when geometry is
 * present, {@code MANUAL} otherwise) before the entity is created/updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomServiceExtendedModel {
    private Long id;
    private Long projectId;
    private Long roomTypeId;
    private String label;
    private RoomGeometry geometry;
    private BigDecimal ceilingHeight;
    private Integer internalCorners;
    private Integer doorCount;
    private Integer windowCount;
    private BigDecimal doorHeight;
    private BigDecimal doorWidth;
    private BigDecimal windowHeight;
    private BigDecimal windowWidth;
    private BigDecimal wallGap;
    private BigDecimal finishGap;
    /** FOR-05-02 Round 5 #2 — narrow place up to 40 cm (mb). */
    private BigDecimal narrowPlace;
    /** FOR-05-02 Round 5 #1 — per-dimension value formulas keyed by dimension field. */
    private java.util.Map<String, String> dimensionFormulas;
    /** FOR-05-02 (R22): read-only AI-import marker; set only by the import attach flow. */
    private boolean aiImported;
    /** FOR-05-02 (R9.4): draft media to attach on create (create-only, never persisted on the room). */
    private java.util.List<com.foremen.service.roomsimport.model.ImportMediaRef> importMedia;
    /** FOR-05-02 (R22.2): the import job to verify on create (create-only, never persisted on the room). */
    private Long importJobId;
    private BigDecimal floorArea;
    private MeasureSource floorAreaSource;
    private BigDecimal wallArea;
    private MeasureSource wallAreaSource;
    private BigDecimal perimeter;
    private MeasureSource perimeterSource;
    private BigDecimal doorArea;
    private MeasureSource doorAreaSource;
    private BigDecimal windowArea;
    private MeasureSource windowAreaSource;
}
