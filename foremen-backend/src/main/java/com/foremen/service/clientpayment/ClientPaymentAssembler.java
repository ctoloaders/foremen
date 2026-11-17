package com.foremen.service.clientpayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.ClientPaymentDto;
import com.foremen.controller.model.ClientPaymentLineSummaryDto;
import com.foremen.controller.model.ClientPaymentView;
import com.foremen.controller.model.client.ClientPaymentClientDto;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.service.EffectiveBankAccountResolver;
import com.foremen.service.payment.ScheduleStatus;

/**
 * FOR-11-06 (R1.3, R1.4, R1.7; design 2): builds the read DTO of a {@link ClientPaymentEntity}.
 * The display status always comes from {@link ClientPaymentStatusMapper}. A row the role may not see
 * (BUYER: only MATERIALS; FOREMAN: only WORKS; CLIENT: published works and issued / paid materials)
 * yields an empty result, never a DTO. A CLIENT gets the allow-listed {@link ClientPaymentClientDto}.
 */
@Component
public class ClientPaymentAssembler {

    private final EffectiveBankAccountResolver bankAccountResolver;

    public ClientPaymentAssembler(EffectiveBankAccountResolver bankAccountResolver) {
        this.bankAccountResolver = bankAccountResolver;
    }

    /** Looks the effective account up through the resolver. {@code lines} is used for MATERIALS only. */
    public Optional<ClientPaymentView> assemble(ClientPaymentEntity p, ClientPaymentVisibility visibility,
                                                LocalDate today, List<ClientProformaLineEntity> lines) {
        return assemble(p, visibility, today, lines, List.of());
    }

    /** As above; {@code payments} (ledger payments of the proforma) is shown to staff only. */
    public Optional<ClientPaymentView> assemble(ClientPaymentEntity p, ClientPaymentVisibility visibility,
                                                LocalDate today, List<ClientProformaLineEntity> lines,
                                                List<ClientPaymentDto.PaymentSummary> payments) {
        if (!isVisible(p, visibility)) {
            return Optional.empty();
        }
        return Optional.of(build(p, visibility, today, lines, bankAccountResolver.forProject(p.getProject()),
                payments));
    }

    /** True when the role may see the row at all (kind rule, plus the client row rule). */
    public static boolean isVisible(ClientPaymentEntity p, ClientPaymentVisibility visibility) {
        if (p == null || visibility == null || !visibility.allows(p.getKind())) {
            return false;
        }
        if (visibility != ClientPaymentVisibility.CLIENT) {
            return true;
        }
        if (p.getKind() == ClientPaymentKind.MATERIALS) {
            return ClientPaymentVisibility.CLIENT_MATERIALS_STATUSES.contains(p.getStatus());
        }
        PaymentTrancheEntity t = p.getTranche();
        return t != null && t.getSchedule() != null && t.getSchedule().getStatus() == ScheduleStatus.PUBLISHED;
    }

    /** Pure core: the caller has already checked {@link #isVisible}. */
    static ClientPaymentView build(ClientPaymentEntity p, ClientPaymentVisibility visibility, LocalDate today,
                                   List<ClientProformaLineEntity> lines, String effectiveAccount) {
        return build(p, visibility, today, lines, effectiveAccount, List.of());
    }

    static ClientPaymentView build(ClientPaymentEntity p, ClientPaymentVisibility visibility, LocalDate today,
                                   List<ClientProformaLineEntity> lines, String effectiveAccount,
                                   List<ClientPaymentDto.PaymentSummary> payments) {
        ClientPaymentKind kind = p.getKind();
        DisplayStatus display = ClientPaymentStatusMapper.toDisplay(kind, p.getStatus(), p.getDueDate(), today);
        boolean overdue = ClientPaymentStatusMapper.overdue(kind, p.getStatus(), p.getDueDate(), today);
        BigDecimal amount = nz(p.getAmountGross());
        BigDecimal paid = nz(p.getPaidGross());
        BigDecimal remaining = "CANCELLED".equals(p.getStatus())
                ? BigDecimal.ZERO : amount.subtract(paid).max(BigDecimal.ZERO);
        ClientProformaEntity pf = kind == ClientPaymentKind.MATERIALS ? p.getProforma() : null;
        List<ClientPaymentLineSummaryDto> lineDtos = pf == null || lines == null ? List.of()
                : lines.stream().filter(ClientProformaLineEntity::isActive)
                .map(ClientPaymentAssembler::line).toList();

        if (visibility == ClientPaymentVisibility.CLIENT) {
            String snapshot = p.getBankAccountSnapshot();
            String payee = snapshot != null && !snapshot.isBlank() ? snapshot : effectiveAccount;
            String number = pf == null ? null : pf.getNumber();
            return new ClientPaymentClientDto(p.getId(), kind, display, overdue, number,
                    pf == null ? null : pf.getIssueDate(), p.getDueDate(), p.getCurrency(), amount, paid,
                    remaining, payee, number == null ? null : "Proforma " + number, lineDtos);
        }

        // R4.4: the payee account is not exposed to FOREMAN and BUYER.
        boolean accountHidden = visibility == ClientPaymentVisibility.WORKS_ONLY
                || visibility == ClientPaymentVisibility.MATERIALS_ONLY;
        String staffAccount = accountHidden ? null : effectiveAccount;
        ClientPaymentDto.WorksBlock works = null;
        ClientPaymentDto.ProformaBlock proforma = null;
        if (kind == ClientPaymentKind.WORKS) {
            PaymentTrancheEntity t = p.getTranche();
            if (t != null) {
                works = new ClientPaymentDto.WorksBlock(t.getId(),
                        t.getSchedule() == null ? null : t.getSchedule().getId(), t.getSeqNo(),
                        t.getSchedule() == null ? null : t.getSchedule().getStatus());
            }
        } else if (pf != null) {
            proforma = new ClientPaymentDto.ProformaBlock(pf.getId(), pf.getNumber(), pf.getIssueDate(),
                    pf.getTotalNet(), pf.getTotalVat(), pf.getReleasedAt(), pf.getCancelledAt(),
                    pf.getCancelReason(), pf.getPdfLanguage(),
                    pf.getPdfObjectKey() != null && !pf.getPdfObjectKey().isBlank(),
                    accountHidden ? null : p.getBankAccountSnapshot(), lineDtos, payments == null ? List.of() : payments);
        }
        return new ClientPaymentDto(p.getId(), p.getProject() == null ? null : p.getProject().getId(), kind,
                display, p.getStatus(), overdue, p.getDueDate(), p.getCurrency(), amount, paid, remaining,
                p.getNote(), staffAccount, works, proforma);
    }

    private static ClientPaymentLineSummaryDto line(ClientProformaLineEntity l) {
        return new ClientPaymentLineSummaryDto(l.getLineNo(), l.getDescription(), l.getSku(), l.getQuantity(),
                l.getUnitName(), l.getNet(), l.getGross());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
