package com.foremen.service.readiness;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.RoomEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link RoomsReadinessContributor} (FOR-05-11, Property 7 — rooms).
 *
 * <p>For random room populations the derived {@code rooms} contribution must satisfy:
 *
 * <ul>
 *   <li><b>Empty.</b> No rooms → {@link ReadinessState#BLOCKED}, {@code 0%}, exactly one
 *       {@code readiness.rooms.none} item.</li>
 *   <li><b>Fraction.</b> With rooms, {@code percentage == round(100 × withPositiveArea / total)},
 *       always in {@code [0, 100]}.</li>
 *   <li><b>State boundary.</b> {@link ReadinessState#DONE} iff every room has a positive floor area,
 *       otherwise {@link ReadinessState#PARTIAL} (never {@code BLOCKED} once a room exists).</li>
 *   <li><b>Items.</b> One {@code readiness.rooms.noDimensions} item per room without a positive
 *       floor area, ordered by room id (the DAO's order), each carrying the room's {@code roomName}
 *       param; empty items iff {@code DONE}.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-11-project-readiness, Property 7 (rooms)
 *
 * <p><b>Validates: Requirements 1.3, 5.1, 5.7, 5.8</b>
 */
@Tag("Feature: FOR-05-11-project-readiness, Property 7 (rooms)")
class RoomsReadinessContributorPropertyTest {

    @Property(tries = 100)
    void noRoomsIsBlockedAtZeroWithFirstStepItem(@ForAll("noRooms") List<RoomEntity> rooms) {
        AreaContribution contribution = RoomsReadinessContributor.deriveContribution(rooms);

        assertThat(contribution.key()).isEqualTo("rooms");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.rooms");
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.rooms.none");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("rooms");
        });
    }

    @Property(tries = 300)
    void percentageEqualsRoundedPositiveAreaFraction(@ForAll("rooms") List<RoomEntity> rooms) {
        AreaContribution contribution = RoomsReadinessContributor.deriveContribution(rooms);

        long withArea = rooms.stream().filter(RoomsReadinessContributorPropertyTest::hasPositiveArea).count();
        int expected = (int) Math.round(100.0 * withArea / rooms.size());

        assertThat(contribution.percentage()).isEqualTo(expected);
        assertThat(contribution.percentage()).isBetween(0, 100);
    }

    @Property(tries = 300)
    void stateIsDoneIffEveryRoomHasPositiveArea(@ForAll("rooms") List<RoomEntity> rooms) {
        AreaContribution contribution = RoomsReadinessContributor.deriveContribution(rooms);

        boolean allHaveArea = rooms.stream().allMatch(RoomsReadinessContributorPropertyTest::hasPositiveArea);

        if (allHaveArea) {
            assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
            assertThat(contribution.percentage()).isEqualTo(100);
        } else {
            assertThat(contribution.state()).isEqualTo(ReadinessState.PARTIAL);
        }
        // once a room exists the area is never BLOCKED
        assertThat(contribution.state()).isNotEqualTo(ReadinessState.BLOCKED);
    }

    @Property(tries = 300)
    void itemsAreOneNoDimensionsPerRoomWithoutAreaOrderedById(@ForAll("rooms") List<RoomEntity> rooms) {
        AreaContribution contribution = RoomsReadinessContributor.deriveContribution(rooms);

        List<Long> expectedRoomIds = rooms.stream()
                .filter(room -> !hasPositiveArea(room))
                .map(RoomEntity::getId)
                .toList();

        assertThat(contribution.items()).hasSize(expectedRoomIds.size());

        List<Long> actualRoomIds = new ArrayList<>();
        for (OutstandingItem item : contribution.items()) {
            assertThat(item.descriptionKey()).isEqualTo("readiness.rooms.noDimensions");
            assertThat(item.targetTab()).isEqualTo("rooms");
            assertThat(item.params()).containsOnlyKeys("roomName");
            actualRoomIds.add(Long.valueOf(String.valueOf(item.params().get("roomName"))));
        }

        // rooms have null labels here, so roomName falls back to the id — and must be in id order
        assertThat(actualRoomIds).isEqualTo(expectedRoomIds);
    }

    @Property(tries = 200)
    void itemsEmptyIffDone(@ForAll("rooms") List<RoomEntity> rooms) {
        AreaContribution contribution = RoomsReadinessContributor.deriveContribution(rooms);

        boolean fullyReady = contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
        assertThat(contribution.items().isEmpty()).isEqualTo(fullyReady);
    }

    // --- Providers ---

    private static boolean hasPositiveArea(RoomEntity room) {
        BigDecimal floorArea = room.getFloorArea();
        return floorArea != null && floorArea.signum() > 0;
    }

    /** The empty-rooms case. */
    @Provide
    Arbitrary<List<RoomEntity>> noRooms() {
        return Arbitraries.just(List.of());
    }

    /**
     * A non-empty list of rooms with ascending ids (as the DAO returns them, ordered by id) and a
     * floor area that is null, zero, or positive — exercising the with/without-area partition and
     * the {@code null}/{@code 0}/positive boundary.
     */
    @Provide
    Arbitrary<List<RoomEntity>> rooms() {
        Arbitrary<FloorArea> floorArea = Arbitraries.of(FloorArea.class);
        return floorArea.list().ofMinSize(1).ofMaxSize(8).map(areas -> {
            List<RoomEntity> rooms = new ArrayList<>();
            long id = 1L;
            for (FloorArea area : areas) {
                rooms.add(room(id++, area));
            }
            return rooms;
        });
    }

    private static RoomEntity room(long id, FloorArea area) {
        RoomEntity room = new RoomEntity();
        room.setId(id);
        room.setFloorArea(switch (area) {
            case NULL -> null;
            case ZERO -> BigDecimal.ZERO;
            case POSITIVE -> new BigDecimal("12.50");
        });
        return room;
    }

    private enum FloorArea { NULL, ZERO, POSITIVE }
}
