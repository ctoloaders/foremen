package com.foremen.dao.model;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The PER-PACKAGE link (FOR-05-05 Wave 1b, #8) from an {@link AssortmentPositionEntity} to the
 * {@link WorkItemEntity} whose finishing consumption it fulfils UNDER a specific
 * {@link OfferPackageEntity}. Replaces the single {@code assortment_positions.work_item_id} FK
 * (Amendment A1): a position may now link to a DIFFERENT work per package.
 *
 * <p>Maps the join table {@code assortment_position_work_items} (changeset 120). The
 * {@code (assortment_position_id, offer_package_id)} pair is the composite primary key — one work
 * per position per package — and all three FKs are {@code ON DELETE CASCADE} (matching the
 * {@code work_room_types} / {@code assortment_group_room_types} join-table convention): removing the
 * position, the package, or the linked work removes the link row.
 *
 * <p>This standalone entity does not extend {@link BaseEntity} (the join table carries no surrogate
 * id / audit columns, mirroring the pure-join tables); its identity is the {@code (position,
 * offerPackage)} pair, so {@code position} + {@code offerPackage} form the natural key.
 */
@Entity
@Table(name = "assortment_position_work_items")
@IdClass(AssortmentPositionWorkItemEntity.PositionPackageId.class)
@Getter
@Setter
@NoArgsConstructor
public class AssortmentPositionWorkItemEntity {

    /** Owner FK: deleting the position removes its per-package work links (ON DELETE CASCADE). */
    @Id
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assortment_position_id", nullable = false)
    private AssortmentPositionEntity position;

    /** The offer package this link applies to (ON DELETE CASCADE); part of the composite key. */
    @Id
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    /** The work item whose finishing consumption this position fulfils for the package (ON DELETE CASCADE). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    /**
     * Composite primary key of {@link AssortmentPositionWorkItemEntity}: the {@code (position,
     * offerPackage)} pair. The field names/types match the two {@code @Id} association fields (by
     * the {@code @IdClass} contract, the id type of each associated entity — {@link Long} for both).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PositionPackageId implements Serializable {
        private Long position;
        private Long offerPackage;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PositionPackageId that = (PositionPackageId) o;
            return java.util.Objects.equals(position, that.position)
                    && java.util.Objects.equals(offerPackage, that.offerPackage);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(position, offerPackage);
        }
    }
}
