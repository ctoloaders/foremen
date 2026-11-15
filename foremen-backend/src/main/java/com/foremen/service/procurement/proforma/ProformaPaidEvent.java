package com.foremen.service.procurement.proforma;

import java.util.List;

/**
 * FOR-11-03 (R9.1): a proforma was fully paid and its positions were released to the buyer
 * ({@code IN_PROFORMA -> CLIENT_PAID -> WITH_BUYER}). Published inside the release transaction and
 * consumed after commit by the notification emitter (task 6.1).
 *
 * @param proformaId  the paid proforma
 * @param projectId   its project
 * @param positionIds the positions actually released (skipped ones are not listed)
 * @param actorUserId the ledger user who linked the payment; {@code null} for a system replay
 */
public record ProformaPaidEvent(Long proformaId, Long projectId, List<Long> positionIds, Long actorUserId) {
}
