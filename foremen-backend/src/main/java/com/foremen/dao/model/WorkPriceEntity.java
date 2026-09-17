package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "work_prices")
@Getter
@Setter
@NoArgsConstructor
public class WorkPriceEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false, unique = true)
    private WorkItemEntity workItem;

    @OneToMany(mappedBy = "workPrice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkPackagePriceEntity> packagePrices = new ArrayList<>();
}
