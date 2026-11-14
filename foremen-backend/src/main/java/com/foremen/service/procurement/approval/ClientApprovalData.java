package com.foremen.service.procurement.approval;

import java.util.List;
import java.util.Map;

/**
 * Result of {@link ApprovalQueryService#loadClientData}: positions in display order, their rounds by
 * position id (ordered by round number) and options by round id (ordered by sort order).
 */
public record ClientApprovalData(List<ClientPositionSource> positions,
                                 Map<Long, List<ClientRoundSource>> roundsByPosition,
                                 Map<Long, List<ClientOptionSource>> optionsByRound) {
}
