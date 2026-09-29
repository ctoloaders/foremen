package com.foremen.dao.model;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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

    /**
     * Optional Room_Type_Attachment (FOR-05-05, design B2 / R10): the set of room types this work
     * attaches to when a package or the work-row hammer applies. Empty ⇒ the work attaches to ALL
     * rooms on apply (R10.3); non-empty ⇒ only rooms whose type is in the set (R10.2). The attachment
     * governs WHICH rooms a work attaches to, never HOW the Volume is computed (R10.4) — the volume
     * formula stays independent.
     *
     * <p>Maps the pure M:N join {@code work_room_types(work_item_id, room_type_id)} (changeset 103),
     * whose DB-level FKs are {@code ON DELETE CASCADE} and whose {@code (work_item_id, room_type_id)}
     * pair is the composite primary key.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "work_room_types",
            joinColumns = @JoinColumn(name = "work_item_id"),
            inverseJoinColumns = @JoinColumn(name = "room_type_id"))
    private Set<RoomTypeEntity> roomTypes = new HashSet<>();
}
