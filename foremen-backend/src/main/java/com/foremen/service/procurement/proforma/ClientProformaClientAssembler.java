package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.client.ClientProformaClientDto;
import com.foremen.controller.model.client.ClientProformaLineClientDto;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;

/**
 * FOR-11-03 (R6.2, R6.3): builds the client DTO from a proforma and its lines. Reads an explicit
 * allow-list of fields; nothing else of the entities can reach the client.
 */
@Component
public class ClientProformaClientAssembler {

    /** R6.2: the client never sees a DRAFT. */
    public static final Set<ProformaStatus> CLIENT_VISIBLE = Set.of(
            ProformaStatus.ISSUED, ProformaStatus.PARTIALLY_PAID, ProformaStatus.PAID, ProformaStatus.CANCELLED);

    public ClientProformaClientDto assemble(ClientProformaEntity p, List<ClientProformaLineEntity> lines,
                                            LocalDate today) {
        BigDecimal paid = p.getPaidGross() == null ? BigDecimal.ZERO : p.getPaidGross();
        BigDecimal remaining = p.getStatus() == ProformaStatus.CANCELLED
                ? BigDecimal.ZERO : p.getTotalGross().subtract(paid).max(BigDecimal.ZERO);
        boolean overdue = p.getDueDate() != null && p.getDueDate().isBefore(today)
                && (p.getStatus() == ProformaStatus.ISSUED || p.getStatus() == ProformaStatus.PARTIALLY_PAID);
        List<ClientProformaLineClientDto> dtoLines = lines.stream().map(l -> new ClientProformaLineClientDto(
                l.getLineNo(), l.getDescription(), l.getSku(), l.getQuantity(), l.getUnitName(),
                l.getUnitSaleNet(), l.getVatRatePercent(), l.getNet(), l.getVat(), l.getGross())).toList();
        return new ClientProformaClientDto(p.getId(), p.getNumber(), p.getStatus(), p.getIssueDate(),
                p.getDueDate(), overdue, p.getCurrency(), p.getTotalNet(), p.getTotalVat(), p.getTotalGross(),
                paid, remaining, "Proforma " + p.getNumber(), dtoLines);
    }
}
