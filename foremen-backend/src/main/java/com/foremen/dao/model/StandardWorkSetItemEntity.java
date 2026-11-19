package com.foremen.dao.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row of the standard work set (FOR-04-11 additional tasks 6-8): the work belongs to the standard
 * set of the room type. When a room of that type is added to a project, the works of the set are
 * applied to the project's estimate. Company-wide (no project scope); each {@code (roomType, work)}
 * pair appears at most once.
 */
@Entity
@Table(name = "standard_work_set_items")
@Getter
@Setter
@NoArgsConstructor
public class StandardWorkSetItemEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomTypeEntity roomType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;
}
