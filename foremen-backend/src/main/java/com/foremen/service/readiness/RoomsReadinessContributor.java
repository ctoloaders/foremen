package com.foremen.service.readiness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.RoomDao;
import com.foremen.dao.model.RoomEntity;

/**
 * The {@code rooms} area's readiness contribution (FOR-05-11 Requirement 5.1, Decision D12).
 *
 * <p>Combines the parent's {@code rooms} and {@code dimensions} gates into one equally-weighted
 * {@code rooms} area that maps 1:1 to the {@code rooms} workspace tab. The room count drives the
 * {@code BLOCKED}/first-step case and the per-room missing-dimension facts surface as the area's
 * outstanding items.
 *
 * <ul>
 *   <li>No rooms → {@link ReadinessState#BLOCKED}, {@code 0%}, one {@code readiness.rooms.none}
 *       item (first step: add a room).</li>
 *   <li>Otherwise {@code percentage = round(100 × roomsWithPositiveArea / totalRooms)}; the area is
 *       {@link ReadinessState#DONE} at {@code 100%} iff every room has a positive floor area, and
 *       {@link ReadinessState#PARTIAL} otherwise, listing one {@code readiness.rooms.noDimensions}
 *       item per room without a positive floor area (params = the room's name).</li>
 * </ul>
 *
 * <p>Rooms are read via {@link RoomDao#findByProjectId(Long)}, which orders by room id, so the
 * outstanding items are deterministically ordered by room id (Requirement 5.8). This contributor is
 * side-effect free (Requirement 1.6).
 */
@Component
public class RoomsReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "rooms";
    static final String NAME_KEY = "workspace.tab.rooms";
    static final String TARGET_TAB = "rooms";
    static final String ITEM_NONE = "readiness.rooms.none";
    static final String ITEM_NO_DIMENSIONS = "readiness.rooms.noDimensions";

    private final RoomDao roomDao;

    public RoomsReadinessContributor(RoomDao roomDao) {
        this.roomDao = roomDao;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "ROOMS";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        return deriveContribution(roomDao.findByProjectId(projectId));
    }

    /**
     * The pure derivation of the {@code rooms} contribution from the project's rooms, ordered by
     * room id. Spring-free so it is directly property-testable (FOR-05-11 Property 7, rooms).
     *
     * @param rooms the project's rooms, ordered by id (may be empty)
     * @return the well-formed {@code rooms} area contribution
     */
    static AreaContribution deriveContribution(List<RoomEntity> rooms) {
        if (rooms.isEmpty()) {
            return new AreaContribution(
                    AREA_KEY,
                    NAME_KEY,
                    0,
                    ReadinessState.BLOCKED,
                    List.of(new OutstandingItem(ITEM_NONE, Map.of(), TARGET_TAB)));
        }

        int total = rooms.size();
        int withArea = 0;
        List<OutstandingItem> items = new ArrayList<>();
        for (RoomEntity room : rooms) {
            if (hasPositiveArea(room)) {
                withArea++;
            } else {
                items.add(new OutstandingItem(
                        ITEM_NO_DIMENSIONS,
                        Map.of("roomName", roomName(room)),
                        TARGET_TAB));
            }
        }

        int percentage = Math.toIntExact(Math.round(100.0 * withArea / total));
        ReadinessState state = withArea == total ? ReadinessState.DONE : ReadinessState.PARTIAL;
        return new AreaContribution(AREA_KEY, NAME_KEY, percentage, state, List.copyOf(items));
    }

    private static boolean hasPositiveArea(RoomEntity room) {
        BigDecimal floorArea = room.getFloorArea();
        return floorArea != null && floorArea.signum() > 0;
    }

    private static String roomName(RoomEntity room) {
        String label = room.getLabel();
        if (label != null && !label.isBlank()) {
            return label;
        }
        return String.valueOf(room.getId());
    }
}
