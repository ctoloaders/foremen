package com.foremen.collab;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.controller.model.ApplyAssignmentsRequest.StagedEditPayload;

/**
 * FOR-05-13 — the wire contract of the collaborative estimate. Everything is exchanged as JSON over
 * STOMP ({@code /topic/estimate/{projectId}} out, {@code /app/estimate/{projectId}/*} in) plus one
 * REST snapshot ({@code GET /api/estimates/project/{projectId}/collab}).
 *
 * <p><b>Merge model.</b> The shared draft is an ordered list of staged edits keyed by the client
 * {@code stagedId} and ordered by a server-assigned monotonic {@code seq}. Replaying the list in
 * {@code seq} order (exactly what the estimate preview/Save already does with a staged list) gives
 * "edits to different cells merge, the later edit to the same cell wins" — the user-approved
 * merge + last-wins rule — without a separate cell-key fold.
 */
public final class CollabMessages {

    private CollabMessages() {
    }

    /** A cell coordinate in the Works × Rooms matrix. */
    public record CellRef(Long workItemId, Long roomId) {}

    /** One shared-draft entry: a staged edit plus its server sequence and author. */
    public record DraftEntry(
            long seq,
            String stagedId,
            Long authorUserId,
            String authorName,
            int colorIndex,
            StagedEditPayload payload) {}

    /** One connected editor (per STOMP session; a user with two tabs appears twice, the UI dedupes). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PresenceEntry(
            String sessionId,
            Long userId,
            String name,
            int colorIndex,
            CellRef focus,
            long lastSeenEpochMs) {}

    /** Client → server: add or remove one shared-draft entry. */
    public record OpMessage(String type, String stagedId, StagedEditPayload payload) {
        public static final String ADD = "ADD";
        public static final String REMOVE = "REMOVE";
    }

    /** Client → server: the cell this editor is focused on ({@code null} ids = no focus) / heartbeat. */
    public record FocusMessage(Long workItemId, Long roomId) {}

    /** Server → clients: one event on {@code /topic/estimate/{projectId}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollabEvent(
            String type,
            Long projectId,
            DraftEntry entry,
            String stagedId,
            List<String> stagedIds,
            List<PresenceEntry> presence,
            Long actorUserId,
            String actorName) {

        public static final String EDIT_ADDED = "EDIT_ADDED";
        public static final String EDIT_REMOVED = "EDIT_REMOVED";
        public static final String PRESENCE = "PRESENCE";
        public static final String SAVED = "SAVED";

        static CollabEvent editAdded(Long projectId, DraftEntry entry) {
            return new CollabEvent(EDIT_ADDED, projectId, entry, entry.stagedId(), null, null,
                    entry.authorUserId(), entry.authorName());
        }

        static CollabEvent editRemoved(Long projectId, String stagedId, Long actorUserId, String actorName) {
            return new CollabEvent(EDIT_REMOVED, projectId, null, stagedId, null, null, actorUserId, actorName);
        }

        static CollabEvent presence(Long projectId, List<PresenceEntry> presence) {
            return new CollabEvent(PRESENCE, projectId, null, null, null, presence, null, null);
        }

        static CollabEvent saved(Long projectId, List<String> stagedIds, Long actorUserId, String actorName) {
            return new CollabEvent(SAVED, projectId, null, null, stagedIds, null, actorUserId, actorName);
        }
    }

    /** The REST catch-up snapshot a newly-joining client seeds from. */
    public record CollabSnapshot(boolean enabled, List<DraftEntry> edits, List<PresenceEntry> presence) {}
}
