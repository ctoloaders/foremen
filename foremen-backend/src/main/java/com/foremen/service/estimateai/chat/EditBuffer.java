package com.foremen.service.estimateai.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;

/**
 * FOR-05-04 (design B8, step 3) — the per-turn buffer the write tools append to. Nothing is persisted:
 * {@code OfferAiChatService} replays {@link #edits()} through the scratch service at the end of the turn.
 *
 * <p>Besides the {@link StagedEdit}s it keeps the model-side {@link CellMetaInput}s (one per touched
 * cell, merged when several tools touch the same cell), the line comments (there is no staged edit kind
 * for a comment, the service applies them to the resulting snapshot), the set of touched work items and
 * the "wide change" flag that drive the confirmation threshold (Requirement 11.5).
 */
public final class EditBuffer {

    /** A line comment to set on the work's line. */
    public record LineComment(Long workItemId, String workCode, String text, String reason) {
    }

    private final List<StagedEdit> edits = new ArrayList<>();
    private final Map<String, CellMetaInput> metas = new LinkedHashMap<>();
    private final List<LineComment> comments = new ArrayList<>();
    private final Set<Long> touchedWorkIds = new LinkedHashSet<>();
    private final Map<String, Boolean> cellState = new LinkedHashMap<>();
    private final Map<Long, Long> categoryByWork = new LinkedHashMap<>();
    private boolean wide;

    public List<StagedEdit> edits() {
        return List.copyOf(edits);
    }

    public List<CellMetaInput> metaInputs() {
        return List.copyOf(metas.values());
    }

    public List<LineComment> comments() {
        return List.copyOf(comments);
    }

    /** Work items changed by this turn so far. */
    public Set<Long> touchedWorkIds() {
        return Set.copyOf(touchedWorkIds);
    }

    /** True when the turn contains an {@code adjust_price} with scope {@code PROJECT}. */
    public boolean wide() {
        return wide;
    }

    public boolean isEmpty() {
        return edits.isEmpty() && comments.isEmpty();
    }

    void add(StagedEdit edit) {
        edits.add(edit);
    }

    void touch(Long workItemId) {
        if (workItemId != null) {
            touchedWorkIds.add(workItemId);
        }
    }

    void markWide() {
        wide = true;
    }

    void addComment(LineComment comment) {
        comments.add(comment);
    }

    void cellAssigned(Long workItemId, Long roomId, Long categoryId) {
        cellState.put(key(workItemId, roomId), Boolean.TRUE);
        if (categoryId != null) {
            categoryByWork.put(workItemId, categoryId);
        }
    }

    void cellRemoved(Long workItemId, Long roomId) {
        cellState.put(key(workItemId, roomId), Boolean.FALSE);
    }

    Map<String, Boolean> cellStates() {
        return cellState;
    }

    Long categoryOf(Long workItemId) {
        return categoryByWork.get(workItemId);
    }

    /**
     * Adds or merges the meta of a cell. A later input overrides non-null texts of an earlier one; an
     * input that carries only a price reason leaves the volume flag of the earlier input untouched.
     */
    void putMeta(CellMetaInput in) {
        String k = in.workCode() + ":" + in.roomId();
        CellMetaInput prev = metas.get(k);
        if (prev == null) {
            metas.put(k, in);
            return;
        }
        boolean priceOnly = in.rationale() == null && in.volumeReason() == null && in.priceReason() != null;
        metas.put(k, new CellMetaInput(in.workCode(), in.roomId(), in.origin(), in.turnId(),
                pick(in.rationale(), prev.rationale()), pick(in.volumeReason(), prev.volumeReason()),
                pick(in.status(), prev.status()), in.confidence() != null ? in.confidence() : prev.confidence(),
                pick(in.sourceFile(), prev.sourceFile()),
                in.sourcePage() != null ? in.sourcePage() : prev.sourcePage(),
                pick(in.priceReason(), prev.priceReason()),
                priceOnly ? prev.volumeOverridden() : in.volumeOverridden()));
    }

    private static String pick(String latest, String earlier) {
        return latest != null ? latest : earlier;
    }

    private static String key(Long workItemId, Long roomId) {
        return workItemId + ":" + roomId;
    }
}
