package com.foremen.service.estimate.recalc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.EstimateRecalcConflictException;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.RoomService;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.model.RoomServiceExtendedModel;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * FOR-05-05 additional tasks 2–6 — the atomic Rooms-tab save with estimate auto-recalculation and the
 * change-impact approval protocol (user decisions 2a–2d).
 *
 * <p>One transaction applies the whole room change set (deletes → updates → creates, through the
 * regular {@link RoomService} CRUD so normalization, project scope and audit are unchanged) and then
 * propagates it into the project's DRAFT estimate:
 * <ul>
 *   <li><b>Updated room</b> → its non-overridden cells are re-derived from the new dimensions
 *       ({@link EstimateAssignmentService#rederiveRoom}); manual Volume / material overrides stay.</li>
 *   <li><b>Created room</b> → the works of a same-room-type sibling are applied to it
 *       ({@link EstimateAssignmentService#replicateFromSibling}).</li>
 *   <li><b>Deleted room</b> → its cells are removed and empty lines dropped
 *       ({@link EstimateAssignmentService#unassignRoom}) before the room row is deleted.</li>
 * </ul>
 * The estimate is then recomputed and diffed against a snapshot taken before any write.
 *
 * <p><b>Approval protocol.</b> When the diff is non-empty and {@code force} is {@code false}, an
 * {@link EstimateRecalcConflictException} is thrown: the transaction rolls back (nothing — neither the
 * room change nor the estimate change — is persisted) and the client receives a 409 listing the
 * changes. Re-sending the identical request with {@code force = true} commits both and publishes an
 * {@link EstimateChangedEvent}. A save that does not affect the estimate commits directly.
 */
@Service
@RequiredArgsConstructor
public class RoomsSaveService {

    private static final String NOT_FOUND = "error.entity.not.found";
    private static final String ADMIN_ROLE = "ADMIN";

    private final RoomService roomService;
    private final RoomDao roomDao;
    private final EstimateDao estimateDao;
    private final EstimateAssignmentService estimateAssignmentService;
    private final EstimateRecomputeService estimateRecomputeService;
    private final DraftGateGuard draftGateGuard;
    private final ProjectAccessCache projectAccessCache;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    /** An existing room's update: its id and the normalized-on-write service model. */
    public record RoomUpdate(Long id, RoomServiceExtendedModel model) {
    }

    /**
     * The outcome of a committed save.
     *
     * @param created        ids of the created rooms, in request order
     * @param updated        ids of the updated rooms
     * @param deleted        ids of the deleted rooms
     * @param estimateImpact what the save changed in the estimate (empty when nothing)
     */
    public record Result(List<Long> created, List<Long> updated, List<Long> deleted, EstimateImpact estimateImpact) {
    }

    /**
     * Applies the room change set and propagates it into the estimate (see class doc).
     *
     * @param projectId the project whose rooms are saved; every room in the set must belong to it
     * @param creates   new rooms (their {@code projectId} must equal {@code projectId})
     * @param updates   edited existing rooms
     * @param deleteIds rooms to delete
     * @param force     {@code true} to persist even when the estimate changes (the approved re-send)
     * @return the committed outcome
     * @throws EstimateRecalcConflictException when {@code force} is {@code false} and the estimate would change
     */
    @Transactional
    public Result save(Long projectId, List<RoomServiceExtendedModel> creates, List<RoomUpdate> updates,
                       List<Long> deleteIds, boolean force) {
        assertProjectAccess(projectId);
        List<RoomServiceExtendedModel> createList = creates == null ? List.of() : creates;
        List<RoomUpdate> updateList = updates == null ? List.of() : updates;
        List<Long> deleteList = deleteIds == null ? List.of() : deleteIds.stream().filter(Objects::nonNull).distinct().toList();

        Optional<EstimateEntity> estimateOpt = estimateDao.findByProjectId(projectId);
        EstimateEntity estimate = estimateOpt
                .filter(e -> e.getStatus() == EstimateStatus.DRAFT)
                .orElse(null);
        EstimateSnapshot before = estimate != null ? EstimateSnapshot.of(estimate) : null;

        // --- 1. Deletes: drop the room's estimate cells first, then the room row. ---
        for (Long roomId : deleteList) {
            RoomEntity room = requireRoomOfProject(roomId, projectId);
            if (estimate != null) {
                estimateAssignmentService.unassignRoom(estimate, room.getId());
            } else {
                estimateOpt.ifPresent(locked -> {
                    if (hasCells(locked, room.getId())) {
                        draftGateGuard.assertDraft(locked); // never cascade-delete cells of a locked estimate
                    }
                });
            }
            roomService.deleteById(roomId);
        }
        Set<Long> deleted = new HashSet<>(deleteList);

        // --- 2. Updates: persist the new dimensions, then re-derive the room's cells. ---
        List<Long> updated = new ArrayList<>();
        for (RoomUpdate update : updateList) {
            if (deleted.contains(update.id())) {
                continue;
            }
            requireRoomOfProject(update.id(), projectId);
            requireSameProject(update.model(), projectId);
            roomService.update(update.id(), update.model());
            updated.add(update.id());
        }
        entityManager.flush();
        if (estimate != null) {
            for (Long roomId : updated) {
                estimateAssignmentService.rederiveRoom(estimate, requireRoomOfProject(roomId, projectId));
            }
        }

        // --- 3. Creates: persist the room, then apply a same-room-type sibling's works. ---
        List<Long> created = new ArrayList<>();
        for (RoomServiceExtendedModel model : createList) {
            requireSameProject(model, projectId);
            created.add(roomService.create(model).getId());
        }
        entityManager.flush();
        if (estimate != null && !created.isEmpty()) {
            Set<Long> createdSet = new HashSet<>(created);
            List<RoomEntity> projectRooms = roomDao.findByProjectId(projectId);
            for (Long roomId : created) {
                RoomEntity newRoom = requireRoomOfProject(roomId, projectId);
                List<RoomEntity> siblings = projectRooms.stream()
                        .filter(r -> !createdSet.contains(r.getId()) && !deleted.contains(r.getId()))
                        .filter(r -> sameRoomType(r, newRoom))
                        .toList();
                estimateAssignmentService.replicateFromSibling(estimate, newRoom, siblings);
            }
        }

        // --- 4. Recompute + diff + approval gate. ---
        EstimateImpact impact = EstimateImpact.none(null);
        if (estimate != null) {
            estimateRecomputeService.recomputeEstimate(estimate);
            entityManager.flush();
            impact = EstimateSnapshot.diff(before, EstimateSnapshot.of(estimate));
            if (!impact.isEmpty() && !force) {
                throw new EstimateRecalcConflictException(impact); // rolls back every write above
            }
            if (!impact.isEmpty()) {
                eventPublisher.publishEvent(new EstimateChangedEvent(
                        projectId, estimate.getId(), currentUserId(), EstimateChangedEvent.Trigger.ROOMS_CHANGED));
            }
        }
        return new Result(List.copyOf(created), List.copyOf(updated), List.copyOf(deleteList), impact);
    }

    // --- helpers ---------------------------------------------------------------------------

    private RoomEntity requireRoomOfProject(Long roomId, Long projectId) {
        RoomEntity room = roomId == null ? null : roomDao.findById(roomId).orElse(null);
        if (room == null || room.getProject() == null || !projectId.equals(room.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "roomId", roomId);
        }
        return room;
    }

    private static void requireSameProject(RoomServiceExtendedModel model, Long projectId) {
        if (model == null || !projectId.equals(model.getProjectId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "projectId",
                    model == null ? null : model.getProjectId());
        }
    }

    private static boolean sameRoomType(RoomEntity a, RoomEntity b) {
        return a.getRoomType() != null && b.getRoomType() != null
                && Objects.equals(a.getRoomType().getId(), b.getRoomType().getId());
    }

    private static boolean hasCells(EstimateEntity estimate, Long roomId) {
        for (EstimateLineEntity line : estimate.getLines()) {
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                if (roomQty.getRoom() != null && roomId.equals(roomQty.getRoom().getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Project scope for the whole set (ADMIN bypass); a non-accessible project reads as 404. */
    private void assertProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "projectId", projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (("ROLE_" + ADMIN_ROLE).equals(ga.getAuthority())) {
                return;
            }
        }
        Long userId = currentUserId();
        Set<Long> allowed = userId == null ? Set.of() : projectAccessCache.get(userId);
        if (projectId == null || allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "projectId", projectId);
        }
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
