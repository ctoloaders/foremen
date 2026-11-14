package com.foremen.controller.model.client;

import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.ClientAction;
import com.foremen.service.procurement.approval.ClientStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** FOR-11-02: Client_View of a position. Built only by {@code ClientViewAssembler}. */
public record ClientApprovalPositionDto(Long positionId, Integer blockNo, ClientItemDto item, BigDecimal quantity,
                                        String unitName, ClientStatus clientStatus, SourcingPath sourcingPath,
                                        ClientMoneyDto money, LocalDate decisionDeadline, boolean overdue,
                                        ClientRoundDto openRound, List<ClientRoundDto> openSuggestions,
                                        List<ClientRoundDto> history, Set<ClientAction> allowedActions) {
}
