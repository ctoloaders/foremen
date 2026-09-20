package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "work_items")
@Getter
@Setter
@NoArgsConstructor
public class WorkItemEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_category_id", nullable = false)
    private WorkCategoryEntity workCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    private MeasurementUnitEntity unit;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    /** Stable natural key from the Excel positional {@code LP} (e.g. {@code 1.01}); nullable, unique among non-null values. */
    @Column(name = "code")
    private String code;

    @Column(nullable = false)
    private boolean active = true;
}
