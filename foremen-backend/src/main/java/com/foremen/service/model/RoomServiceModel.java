package com.foremen.service.model;

import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.RoomGeometry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-path service model for a room: flat metric values paired with their source flags, the
 * derived counts/gaps, the geometry, and the resolved reference ids/names ({@code projectName},
 * {@code roomTypeName} — localized to the request locale by the service mapper).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomServiceModel {
    private Long id;
    private Long projectId;
    private String projectName;
    private Long roomTypeId;
    private String roomTypeName;
    private String label;
    /** FOR-05-02 Round 4: the room's fixed position within its project (read-only). */
    private Integer sortOrder;
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
    /** FOR-05-02 (R10.4): number of attached media files; filled by a grouped count on the read path. */
    private Integer mediaCount;
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
