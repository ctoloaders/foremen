package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class RoomEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomTypeEntity roomType;

    @Column(length = 255)
    private String label;

    /**
     * FOR-05-02 bug-report Round 4 — the room's fixed position within its project (0-based,
     * ascending). Assigned on create (appended last) and changed ONLY by the explicit reorder
     * endpoint; never part of the create/update payloads, so a room edit cannot change it.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geometry", columnDefinition = "jsonb")
    private RoomGeometry geometry;

    @Column(name = "ceiling_height", precision = 12, scale = 2)
    private BigDecimal ceilingHeight;

    @Column(name = "internal_corners")
    private Integer internalCorners;

    @Column(name = "door_count")
    private Integer doorCount;

    @Column(name = "window_count")
    private Integer windowCount;

    @Column(name = "door_height", precision = 12, scale = 2)
    private BigDecimal doorHeight;

    @Column(name = "door_width", precision = 12, scale = 2)
    private BigDecimal doorWidth;

    @Column(name = "window_height", precision = 12, scale = 2)
    private BigDecimal windowHeight;

    @Column(name = "window_width", precision = 12, scale = 2)
    private BigDecimal windowWidth;

    @Column(name = "wall_gap", precision = 12, scale = 2)
    private BigDecimal wallGap;

    @Column(name = "finish_gap", precision = 12, scale = 2)
    private BigDecimal finishGap;

    /**
     * FOR-05-02 bug-report Round 5 #2 — "narrow place up to 40 cm" in running metres (mb). A plain
     * manual dimension, exposed to work formulas as {@code narrowPlace}.
     */
    @Column(name = "narrow_place", precision = 12, scale = 2)
    private BigDecimal narrowPlace;

    /**
     * FOR-05-02 bug-report Round 5 #1 — per-dimension value formulas, keyed by the dimension field
     * name (e.g. {@code {"ceilingHeight": "=2,5+0,2"}}). The numeric column of each such dimension
     * holds the value the backend evaluated on save, so readers (formulas, estimate) are unchanged.
     * {@code null} or absent key: the dimension was typed as a plain number.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimension_formulas", columnDefinition = "jsonb")
    private java.util.Map<String, String> dimensionFormulas;

    @Column(name = "floor_area", precision = 12, scale = 2)
    private BigDecimal floorArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "floor_area_source", length = 20)
    private MeasureSource floorAreaSource;

    @Column(name = "wall_area", precision = 12, scale = 2)
    private BigDecimal wallArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "wall_area_source", length = 20)
    private MeasureSource wallAreaSource;

    @Column(precision = 12, scale = 2)
    private BigDecimal perimeter;

    @Enumerated(EnumType.STRING)
    @Column(name = "perimeter_source", length = 20)
    private MeasureSource perimeterSource;

    @Column(name = "door_area", precision = 12, scale = 2)
    private BigDecimal doorArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "door_area_source", length = 20)
    private MeasureSource doorAreaSource;

    @Column(name = "window_area", precision = 12, scale = 2)
    private BigDecimal windowArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "window_area_source", length = 20)
    private MeasureSource windowAreaSource;
}
