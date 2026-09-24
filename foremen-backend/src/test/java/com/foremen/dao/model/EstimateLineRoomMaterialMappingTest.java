package com.foremen.dao.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Feature: for-05-05-bill-of-materials — entity mapping unit test (task 2.3).
 *
 * <p>Reflection-based structural verification (no Spring context, mirroring
 * {@code BaseEntityStructureTest} / {@code WorkPackageOverrideCarriesNoPriceTest}) that:
 * <ul>
 *   <li>the estimate's copied material collection is keyed uniquely per assignment by
 *       {@code (roomQty, branch, type)} — the {@link EstimateLineRoomMaterialEntity} owner FK is
 *       {@code room_qty_id}, {@code branch} is a string enum, and the two type FKs are
 *       {@code construction_type_id} / {@code finishing_type_id} (R13.4);</li>
 *   <li>the collection cascade-removes with its room-qty (and, transitively, its line): the
 *       {@link EstimateLineRoomQtyEntity#materials} {@code @OneToMany(mappedBy = "roomQty")} is
 *       {@code cascade = ALL} + {@code orphanRemoval = true} (R19.3);</li>
 *   <li>{@link WorkItemEntity#roomTypes} maps the {@code work_room_types} M:N join table (R10.1).</li>
 * </ul>
 *
 * <p>Validates: Requirements 13.4, 19.3, 10.1
 */
class EstimateLineRoomMaterialMappingTest {

    @Nested
    @DisplayName("Material collection is (roomQty, branch, type)-keyed (R13.4)")
    class MaterialLineKeying {

        @Test
        void materialEntityMapsEstimateLineRoomMaterialsTable() {
            Table table = EstimateLineRoomMaterialEntity.class.getAnnotation(Table.class);

            assertThat(table).as("@Table must be present").isNotNull();
            assertThat(table.name()).isEqualTo("estimate_line_room_materials");
        }

        @Test
        void ownerIsRoomQtyManyToOneOnRoomQtyIdColumn() throws NoSuchFieldException {
            Field roomQty = EstimateLineRoomMaterialEntity.class.getDeclaredField("roomQty");

            ManyToOne manyToOne = roomQty.getAnnotation(ManyToOne.class);
            assertThat(manyToOne).as("roomQty must be @ManyToOne").isNotNull();
            assertThat(manyToOne.optional())
                    .as("the owning room-qty is mandatory")
                    .isFalse();

            assertThat(roomQty.getType())
                    .as("owner is the room-qty entity")
                    .isEqualTo(EstimateLineRoomQtyEntity.class);

            JoinColumn joinColumn = roomQty.getAnnotation(JoinColumn.class);
            assertThat(joinColumn).as("@JoinColumn must be present").isNotNull();
            assertThat(joinColumn.name()).isEqualTo("room_qty_id");
            assertThat(joinColumn.nullable()).isFalse();
        }

        @Test
        void branchIsStringEnumPartOfTheKey() throws NoSuchFieldException {
            Field branch = EstimateLineRoomMaterialEntity.class.getDeclaredField("branch");

            assertThat(branch.getType())
                    .as("branch discriminates construction vs finishing")
                    .isEqualTo(ConsumptionBranch.class);

            Enumerated enumerated = branch.getAnnotation(Enumerated.class);
            assertThat(enumerated).as("branch must be @Enumerated").isNotNull();
            assertThat(enumerated.value())
                    .as("branch is stored as a string so the unique key is stable")
                    .isEqualTo(EnumType.STRING);

            Column column = branch.getAnnotation(Column.class);
            assertThat(column).as("branch @Column must be present").isNotNull();
            assertThat(column.nullable()).as("branch participates in the key").isFalse();
        }

        @Test
        void constructionAndFinishingTypeFksCompleteTheKey() throws NoSuchFieldException {
            Field construction = EstimateLineRoomMaterialEntity.class.getDeclaredField("constructionType");
            assertThat(construction.getType()).isEqualTo(ConstructionMaterialTypeEntity.class);
            assertThat(construction.getAnnotation(ManyToOne.class))
                    .as("constructionType is a @ManyToOne type reference").isNotNull();
            assertThat(construction.getAnnotation(JoinColumn.class).name())
                    .isEqualTo("construction_type_id");

            Field finishing = EstimateLineRoomMaterialEntity.class.getDeclaredField("finishingType");
            assertThat(finishing.getType()).isEqualTo(MaterialTypeEntity.class);
            assertThat(finishing.getAnnotation(ManyToOne.class))
                    .as("finishingType is a @ManyToOne type reference").isNotNull();
            assertThat(finishing.getAnnotation(JoinColumn.class).name())
                    .isEqualTo("finishing_type_id");
        }

        @Test
        void keyingComponentsAreExactlyRoomQtyBranchAndType() {
            // The (roomQty, branch, construction_type_id, finishing_type_id) tuple is the
            // documented unique key (R13.4); confirm all four members are declared on the entity.
            assertThat(Arrays.stream(EstimateLineRoomMaterialEntity.class.getDeclaredFields())
                    .map(Field::getName))
                    .contains("roomQty", "branch", "constructionType", "finishingType");
        }
    }

    @Nested
    @DisplayName("Material collection cascade-removes with its room-qty / line (R19.3)")
    class CascadeRemoval {

        @Test
        void materialsCollectionIsCascadeAllOrphanRemovalMappedByRoomQty() throws NoSuchFieldException {
            Field materials = EstimateLineRoomQtyEntity.class.getDeclaredField("materials");

            OneToMany oneToMany = materials.getAnnotation(OneToMany.class);
            assertThat(oneToMany).as("materials must be @OneToMany").isNotNull();
            assertThat(oneToMany.mappedBy())
                    .as("inverse side is the material row's roomQty owner")
                    .isEqualTo("roomQty");
            assertThat(oneToMany.cascade())
                    .as("cascade ALL so material rows persist/remove with the room-qty")
                    .contains(CascadeType.ALL);
            assertThat(oneToMany.orphanRemoval())
                    .as("orphan removal so pruned rows and a removed room-qty delete their materials (R19.3)")
                    .isTrue();
        }

        @Test
        void behavioralCollectionOwnsMaterialRowsAndOrphanIsRemovable() {
            EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();

            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setRoomQty(roomQty);
            material.setBranch(ConsumptionBranch.finishing);

            roomQty.getMaterials().add(material);

            assertThat(roomQty.getMaterials()).containsExactly(material);
            assertThat(material.getRoomQty()).isSameAs(roomQty);

            // Removing the child from the owning collection is what orphanRemoval turns into a
            // DB delete when the room-qty (or, transitively, its line) is removed.
            roomQty.getMaterials().remove(material);
            assertThat(roomQty.getMaterials()).isEmpty();
        }
    }

    @Nested
    @DisplayName("WorkItem.roomTypes maps work_room_types (R10.1)")
    class WorkRoomTypesMapping {

        @Test
        void roomTypesIsManyToManyOverWorkRoomTypesJoinTable() throws NoSuchFieldException {
            Field roomTypes = WorkItemEntity.class.getDeclaredField("roomTypes");

            assertThat(roomTypes.getAnnotation(ManyToMany.class))
                    .as("roomTypes must be @ManyToMany").isNotNull();

            JoinTable joinTable = roomTypes.getAnnotation(JoinTable.class);
            assertThat(joinTable).as("@JoinTable must be present").isNotNull();
            assertThat(joinTable.name()).isEqualTo("work_room_types");
            assertThat(joinTable.joinColumns()).hasSize(1);
            assertThat(joinTable.joinColumns()[0].name()).isEqualTo("work_item_id");
            assertThat(joinTable.inverseJoinColumns()).hasSize(1);
            assertThat(joinTable.inverseJoinColumns()[0].name()).isEqualTo("room_type_id");
        }

        @Test
        void behavioralRoomTypesDefaultsEmptyAndCollectsRoomTypes() {
            WorkItemEntity work = new WorkItemEntity();

            // Empty attachment ⇒ attaches to all rooms on apply (R10.3): default is an empty set.
            assertThat(work.getRoomTypes()).isEmpty();

            RoomTypeEntity roomType = new RoomTypeEntity();
            work.getRoomTypes().add(roomType);

            assertThat(work.getRoomTypes()).containsExactly(roomType);
        }
    }
}
