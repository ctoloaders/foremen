package com.foremen.service.bank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.bank.ledger.LedgerDtos.LinkTarget;
import com.foremen.service.bank.ledger.LedgerDtos.TargetType;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.service.procurement.externalorder.ExternalOrderMath;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-03 (R10.5, R7.6) - the manual-pick search of the two new link kinds on the ledger target picker.
 *
 * <ul>
 *   <li>{@code PROFORMA_PAYMENT}: proformas in {@code ISSUED} / {@code PARTIALLY_PAID} found by number,
 *       project name, client name or amount;</li>
 *   <li>{@code EXTERNAL_ORDER_PAYMENT}: orders in {@code SENT_FOR_PAYMENT} (and {@code PAID} by the
 *       financier) found by id, seller, reference number, invoice number of a linked document or amount.</li>
 * </ul>
 * Items with an amount equal to the line amount come first. The DTO carries the payable gross and the
 * remainder only; no unit purchase price leaves this class.
 */
@Component
public class LedgerProcurementTargetSearch {

    static final int LIMIT = BankLedgerQueryService.TARGET_LIMIT;

    private final ClientProformaDao proformaDao;
    private final ExternalOrderDao orderDao;
    private final ExternalOrderLineDao orderLineDao;
    private final ExternalOrderDocumentDao orderDocumentDao;
    private final BankTransactionLinkDao linkDao;
    private final ProjectMemberDao memberDao;

    @SuppressWarnings("java:S107")
    public LedgerProcurementTargetSearch(ClientProformaDao proformaDao, ExternalOrderDao orderDao,
                                         ExternalOrderLineDao orderLineDao, ExternalOrderDocumentDao orderDocumentDao,
                                         BankTransactionLinkDao linkDao, ProjectMemberDao memberDao) {
        this.proformaDao = proformaDao;
        this.orderDao = orderDao;
        this.orderLineDao = orderLineDao;
        this.orderDocumentDao = orderDocumentDao;
        this.linkDao = linkDao;
        this.memberDao = memberDao;
    }

    /**
     * Open proformas matching {@code needle} (lower-cased, may be {@code null}).
     *
     * @param lineAmount the line amount magnitude, used to rank an equal remainder first
     */
    @Transactional(readOnly = true)
    public List<LinkTarget> proformas(String needle, Long projectId, BigDecimal lineAmount) {
        Map<Long, List<String>> clientNames = new HashMap<>();
        List<Item> items = new ArrayList<>();
        for (ClientProformaEntity p : proformaDao.findByStatusInWithProject(
                List.of(ProformaStatus.ISSUED, ProformaStatus.PARTIALLY_PAID))) {
            ProjectEntity project = p.getProject();
            if (projectId != null && !projectId.equals(project.getId())) {
                continue;
            }
            BigDecimal total = nz(p.getTotalGross());
            BigDecimal remaining = total.subtract(nz(p.getPaidGross())).max(BigDecimal.ZERO);
            List<String> clients = clientNames.computeIfAbsent(project.getId(), this::clientNames);
            if (needle != null && !(contains(needle, p.getNumber()) || contains(needle, project.getName())
                    || paymentIdMatches(needle, p)
                    || clients.stream().anyMatch(c -> contains(needle, c))
                    || amountMatches(needle, total, remaining))) {
                continue;
            }
            items.add(new Item(new LinkTarget(TargetType.PROFORMA, p.getId(), p.getNumber(), project.getName(),
                    scale(total), scale(remaining), p.getCurrency(), p.getIssueDate(), p.getStatus().name(), null,
                    project.getId(), project.getName(), null, null), remaining, p.getId()));
        }
        return rank(items, lineAmount);
    }

    /** Payable external orders matching {@code needle}. */
    @Transactional(readOnly = true)
    public List<LinkTarget> externalOrders(String needle, Long projectId, BigDecimal lineAmount) {
        List<ExternalOrderEntity> orders = orderDao.findByStatusInWithSeller(
                List.of(ExternalOrderStatus.SENT_FOR_PAYMENT, ExternalOrderStatus.PAID)).stream()
                .filter(o -> o.getStatus() == ExternalOrderStatus.SENT_FOR_PAYMENT
                        || o.getPaidSource() == PaidSource.FINANCIER)
                .filter(o -> projectId == null || projectId.equals(o.getProject().getId()))
                .toList();
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ExternalOrderLineEntity>> lines = new HashMap<>();
        for (ExternalOrderLineEntity l : orderLineDao.findForTotalsByExternalOrderIds(
                orders.stream().map(ExternalOrderEntity::getId).toList())) {
            lines.computeIfAbsent(l.getExternalOrder().getId(), k -> new ArrayList<>()).add(l);
        }
        List<Item> items = new ArrayList<>();
        for (ExternalOrderEntity o : orders) {
            BigDecimal payable = payable(o, lines.getOrDefault(o.getId(), List.of()));
            BigDecimal remaining = payable.subtract(nz(linkDao.sumAmountByExternalOrderId(o.getId())))
                    .max(BigDecimal.ZERO);
            if (needle != null && !(("#" + o.getId()).equals(needle) || String.valueOf(o.getId()).equals(needle)
                    || contains(needle, o.getSeller().getNamePL()) || contains(needle, o.getSeller().getNameRU())
                    || contains(needle, o.getReferenceNumber())
                    || invoiceMatches(needle, o.getId()) || amountMatches(needle, payable, remaining))) {
                continue;
            }
            String seller = o.getSeller().getNamePL();
            ProjectEntity project = o.getProject();
            items.add(new Item(new LinkTarget(TargetType.EXTERNAL_ORDER, o.getId(),
                    seller == null ? "#" + o.getId() : "#" + o.getId() + " · " + seller, o.getReferenceNumber(),
                    scale(payable), scale(remaining), "PLN", o.getSentAt() == null ? null
                    : o.getSentAt().toLocalDate(), o.getStatus().name(), null,
                    project.getId(), project.getName(), null, null), remaining, o.getId()));
        }
        return rank(items, lineAmount);
    }

    /** FOR-11-06 (R1.8): a proforma is also found by its unified client payment id ({@code "#12"} or {@code "12"}). */
    private static boolean paymentIdMatches(String needle, ClientProformaEntity p) {
        if (p.getPayment() == null || p.getPayment().getId() == null) {
            return false;
        }
        String id = String.valueOf(p.getPayment().getId());
        return ("#" + id).equals(needle) || id.equals(needle);
    }

    private boolean invoiceMatches(String needle, Long orderId) {
        return orderDocumentDao.findByExternalOrderId(orderId).stream()
                .map(d -> d.getProcurementDocument())
                .map(ProcurementDocumentEntity::getInvoiceNumber)
                .anyMatch(n -> contains(needle, n));
    }

    private List<String> clientNames(Long projectId) {
        return memberDao.findByProjectIdWithUserAndRole(projectId).stream()
                .filter(m -> m.getUser() != null && m.getProjectRole() != null
                        && "CLIENT".equals(m.getProjectRole().getCode()))
                .map(ProjectMemberEntity::getUser)
                .map(u -> u.getName())
                .filter(n -> n != null)
                .toList();
    }

    private static BigDecimal payable(ExternalOrderEntity o, Collection<ExternalOrderLineEntity> lines) {
        List<ExternalOrderMath.LineInput> inputs = new ArrayList<>();
        for (ExternalOrderLineEntity l : lines) {
            inputs.add(new ExternalOrderMath.LineInput(l.getUnitPurchaseNet(), l.getQuantity(),
                    percent(l.getPurchaseVatRate())));
        }
        return ExternalOrderMath.compute(inputs, o.getDeliveryCostNet(), percent(o.getDeliveryVatRate())).payableGross();
    }

    private static BigDecimal percent(VatRateEntity rate) {
        return rate == null ? null : rate.getRate();
    }

    private record Item(LinkTarget target, BigDecimal remaining, Long id) {
    }

    private static List<LinkTarget> rank(List<Item> items, BigDecimal lineAmount) {
        BigDecimal wanted = lineAmount == null ? null : scale(lineAmount.abs());
        return items.stream()
                .sorted(Comparator
                        .comparing((Item i) -> wanted != null && scale(i.remaining()).compareTo(wanted) == 0 ? 0 : 1)
                        .thenComparing(Item::id, Comparator.reverseOrder()))
                .limit(LIMIT)
                .map(Item::target)
                .toList();
    }

    /** The needle as a number ({@code "1 230,50"}) equal to the total or the remainder. */
    static boolean amountMatches(String needle, BigDecimal total, BigDecimal remaining) {
        Optional<BigDecimal> n = parseAmount(needle);
        return n.isPresent() && (scale(total).compareTo(n.get()) == 0 || scale(remaining).compareTo(n.get()) == 0);
    }

    static Optional<BigDecimal> parseAmount(String needle) {
        if (needle == null) {
            return Optional.empty();
        }
        String s = needle.replace(" ", "").replace(" ", "").replace(',', '.');
        if (!s.matches("\\d+(\\.\\d{1,2})?")) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(s).setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean contains(String needle, String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
