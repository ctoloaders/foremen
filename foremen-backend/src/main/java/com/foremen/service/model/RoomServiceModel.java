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
