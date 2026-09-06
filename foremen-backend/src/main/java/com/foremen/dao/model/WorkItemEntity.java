package com.foremen.dao.model;

import jakarta.persistence.*;
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

    @Column(nullable = false)
    private boolean active = true;
}
