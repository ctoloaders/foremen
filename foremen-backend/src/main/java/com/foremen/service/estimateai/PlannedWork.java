package com.foremen.service.estimateai;

import java.math.BigDecimal;

/**
 * FOR-05-04 — one resolved {@code (work, room)} pair of the plan. {@code volume} is the model's volume
 * (null when it gave none or a negative one); the engine decides the final number (Requirement 6).
 *
 * @param workItemId       id of the active catalog work item the code resolved to
 * @param alternativeGroup optional hint of the model: works of the same group in the same room are exclusive
 */
public record PlannedWork(String workCode, Long workItemId, Long roomId, BigDecimal volume, String volumeUnit,
                          String basis, String status, BigDecimal confidence, String source, String rationale,
                          String volumeReason, String alternativeGroup) {
}
