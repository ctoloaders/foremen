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
