package com.foremen.service.estimate;

import java.math.BigDecimal;

/**
 * FOR-05-04 (design B11): the model-side part of a {@link CellAiMeta} for one {@code (work, room)}
 * cell. Produced by the job assignment path or the chat tools (from the tool
 * {@code reason}); {@code CellMetaOverlay} merges it with the engine facts inside the scratch
 * transaction. No text is invented: a missing reason stays {@code null}.
 *
 * @param workCode         catalog code of the work
 * @param roomId           room of the cell
 * @param origin           {@code JOB} or {@code CHAT}
 * @param turnId           chat turn id, nullable for the job
 * @param rationale        why the work applies to the room, nullable
 * @param volumeReason     explanation of a model/chat volume override, nullable
 * @param status           {@code CONFIRMED} | {@code ASSUMED} | {@code QUESTION}
 * @param confidence       model confidence 0..1, nullable
 * @param sourceFile       evidence file name, nullable
 * @param sourcePage       evidence page, nullable
 * @param priceReason      explanation of a price markup/discount, nullable
 * @param volumeOverridden whether this input comes with a volume override edit
 */
public record CellMetaInput(
        String workCode,
        Long roomId,
        String origin,
        Long turnId,
        String rationale,
        String volumeReason,
        String status,
        BigDecimal confidence,
        String sourceFile,
        Integer sourcePage,
        String priceReason,
        boolean volumeOverridden) {
}
