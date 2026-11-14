package com.foremen.service.procurement.approval;

import java.util.List;
import java.util.Map;

/** Result of {@link StaffApprovalQueryService}: positions in display order, rounds by position, options by round. */
public record StaffApprovalData(List<StaffPositionSource> positions,
                                Map<Long, List<StaffRoundSource>> roundsByPosition,
                                Map<Long, List<StaffOptionSource>> optionsByRound) {
}
