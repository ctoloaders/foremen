package com.foremen.service.estimateai.chat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.foremen.service.offer.OfferSnapshot;

/**
 * FOR-05-04 (design B8) — what a tool run sees: the caller's role (tools never run elevated), the project,
 * the offer snapshot of the turn, the turn id and the per-turn {@link EditBuffer}.
 */
public final class ToolContext {

    private final String roleCode;
    private final Long projectId;
    private final Long offerId;
    private final Long turnId;
    private final OfferSnapshot snapshot;
    private final EditBuffer buffer;

    public ToolContext(String roleCode, Long projectId, Long offerId, Long turnId, OfferSnapshot snapshot,
                       EditBuffer buffer) {
        this.roleCode = roleCode;
        this.projectId = projectId;
        this.offerId = offerId;
        this.turnId = turnId;
        this.snapshot = snapshot;
        this.buffer = buffer == null ? new EditBuffer() : buffer;
    }

    public String roleCode() {
        return roleCode;
    }

    public Long projectId() {
        return projectId;
    }

    public Long offerId() {
        return offerId;
    }

    public Long turnId() {
        return turnId;
    }

    public OfferSnapshot snapshot() {
        return snapshot;
    }

    public EditBuffer buffer() {
        return buffer;
    }

    /** The snapshot row of the work item, if the offer has it. */
    Optional<OfferSnapshot.Row> row(Long workItemId) {
        if (snapshot == null || snapshot.groups() == null) {
            return Optional.empty();
        }
        return snapshot.groups().stream()
                .filter(g -> g.rows() != null)
                .flatMap(g -> g.rows().stream())
                .filter(r -> workItemId.equals(r.workItemId()))
                .findFirst();
    }

    /** Work item id to category id, for the snapshot rows. */
    Map<Long, Long> snapshotCategories() {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (snapshot != null && snapshot.groups() != null) {
            for (OfferSnapshot.Group g : snapshot.groups()) {
                if (g.rows() != null) {
                    g.rows().forEach(r -> out.put(r.workItemId(), g.categoryId()));
                }
            }
        }
        return out;
    }

    /** Work item id to the rooms that currently hold a cell: the snapshot with this turn's edits applied. */
    Map<Long, Set<Long>> currentCells() {
        Map<Long, Set<Long>> out = new LinkedHashMap<>();
        if (snapshot != null && snapshot.groups() != null) {
            for (OfferSnapshot.Group g : snapshot.groups()) {
                if (g.rows() == null) {
                    continue;
                }
                for (OfferSnapshot.Row r : g.rows()) {
                    Set<Long> rooms = out.computeIfAbsent(r.workItemId(), k -> new LinkedHashSet<>());
                    if (r.cells() != null) {
                        r.cells().forEach(c -> rooms.add(c.roomId()));
                    }
                }
            }
        }
        buffer.cellStates().forEach((k, assigned) -> {
            String[] p = k.split(":");
            Long w = Long.valueOf(p[0]);
            Long room = Long.valueOf(p[1]);
            if (Boolean.TRUE.equals(assigned)) {
                out.computeIfAbsent(w, x -> new LinkedHashSet<>()).add(room);
            } else if (out.containsKey(w)) {
                out.get(w).remove(room);
            }
        });
        return out;
    }

    boolean hasCell(Long workItemId, Long roomId) {
        return currentCells().getOrDefault(workItemId, Set.of()).contains(roomId);
    }
}
