package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.bank.detect.MatchInputs.ExternalOrderCandidate;
import com.foremen.service.bank.detect.MatchInputs.ProformaCandidate;
import com.foremen.service.procurement.externalorder.ExternalOrderMath;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-03 (R10.1, R10.3) - the DB reads that turn open client proformas and payable external orders
 * into pure matcher candidates ({@link ProformaCandidate}, {@link ExternalOrderCandidate}). Used by
 * {@link DetectionCandidateProviders} (forward matching, whole open set) and by the reverse matchers
 * (one target). No purchase price leaves this class: only the payable remainder is computed.
 */
@Component
public class ProcurementMatchCandidates {

    static final String CLIENT_ROLE = "CLIENT";
    static final Set<ProformaStatus> OPEN_PROFORMA = EnumSet.of(ProformaStatus.ISSUED, ProformaStatus.PARTIALLY_PAID);
    static final Set<ExternalOrderStatus> PAYABLE_ORDER =
            EnumSet.of(ExternalOrderStatus.SENT_FOR_PAYMENT, ExternalOrderStatus.PAID);

    private final ClientProformaDao proformaDao;
    private final ExternalOrderDao orderDao;
    private final ExternalOrderLineDao orderLineDao;
    private final ExternalOrderDocumentDao orderDocumentDao;
    private final BankTransactionLinkDao linkDao;
    private final ProjectMemberDao memberDao;

    @SuppressWarnings("java:S107")
    public ProcurementMatchCandidates(ClientProformaDao proformaDao, ExternalOrderDao orderDao,
                                      ExternalOrderLineDao orderLineDao, ExternalOrderDocumentDao orderDocumentDao,
                                      BankTransactionLinkDao linkDao, ProjectMemberDao memberDao) {
        this.proformaDao = proformaDao;
        this.orderDao = orderDao;
        this.orderLineDao = orderLineDao;
        this.orderDocumentDao = orderDocumentDao;
        this.linkDao = linkDao;
        this.memberDao = memberDao;
    }

    /** Proformas in {@code ISSUED} / {@code PARTIALLY_PAID} with something left to pay (R10.1). */
    @Transactional(readOnly = true)
    public List<ProformaCandidate> openProformas() {
        List<ProformaCandidate> out = new ArrayList<>();
        for (ClientProformaEntity p : proformaDao.findByStatusInWithProject(OPEN_PROFORMA)) {
            out.addAll(toCandidates(p));
        }
        return out;
    }

    /** The candidates of one proforma (one per CLIENT member), empty when it is not open. */
    @Transactional(readOnly = true)
    public List<ProformaCandidate> proformaCandidates(Long proformaId) {
        return proformaDao.findById(proformaId).map(this::toCandidates).orElse(List.of());
    }

    private List<ProformaCandidate> toCandidates(ClientProformaEntity p) {
        if (!OPEN_PROFORMA.contains(p.getStatus()) || p.getNumber() == null) {
            return List.of();
        }
        BigDecimal remainder = nz(p.getTotalGross()).subtract(nz(p.getPaidGross())).setScale(2, RoundingMode.HALF_UP);
        if (remainder.signum() <= 0) {
            return List.of();
        }
        long projectId = p.getProject().getId();
        List<ProjectMemberEntity> clients = memberDao.findByProjectIdWithUserAndRole(projectId).stream()
                .filter(m -> m.getUser() != null && m.getProjectRole() != null
                        && CLIENT_ROLE.equals(m.getProjectRole().getCode()))
                .toList();
        if (clients.isEmpty()) {
            return List.of(new ProformaCandidate(p.getId(), projectId, p.getNumber(), remainder, null, null));
        }
        List<ProformaCandidate> out = new ArrayList<>();
        for (ProjectMemberEntity m : clients) {
            out.add(new ProformaCandidate(p.getId(), projectId, p.getNumber(), remainder,
                    m.getUser().getName(), m.getUser().getNip()));
        }
        return out;
    }

    /** Orders in {@code SENT_FOR_PAYMENT}, and {@code PAID} by the financier, with a payable remainder. */
    @Transactional(readOnly = true)
    public List<ExternalOrderCandidate> payableExternalOrders() {
        List<ExternalOrderEntity> orders = orderDao.findByStatusInWithSeller(PAYABLE_ORDER).stream()
                .filter(ProcurementMatchCandidates::payable)
                .toList();
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ExternalOrderLineEntity>> lines = new java.util.HashMap<>();
        for (ExternalOrderLineEntity l : orderLineDao.findForTotalsByExternalOrderIds(
                orders.stream().map(ExternalOrderEntity::getId).toList())) {
            lines.computeIfAbsent(l.getExternalOrder().getId(), k -> new ArrayList<>()).add(l);
        }
        List<ExternalOrderCandidate> out = new ArrayList<>();
        for (ExternalOrderEntity o : orders) {
            toCandidate(o, lines.getOrDefault(o.getId(), List.of())).ifPresent(out::add);
        }
        return out;
    }

    /** The candidate of one order, empty when it is not payable or fully linked. */
    @Transactional(readOnly = true)
    public java.util.Optional<ExternalOrderCandidate> externalOrderCandidate(Long orderId) {
        return orderDao.findById(orderId)
                .filter(ProcurementMatchCandidates::payable)
                .flatMap(o -> toCandidate(o, orderLineDao.findForTotalsByExternalOrderIds(List.of(o.getId()))));
    }

    private static boolean payable(ExternalOrderEntity o) {
        return o.getStatus() == ExternalOrderStatus.SENT_FOR_PAYMENT
                || (o.getStatus() == ExternalOrderStatus.PAID && o.getPaidSource() == PaidSource.FINANCIER);
    }

    private java.util.Optional<ExternalOrderCandidate> toCandidate(ExternalOrderEntity o,
                                                                    Collection<ExternalOrderLineEntity> lines) {
        List<ExternalOrderMath.LineInput> inputs = new ArrayList<>();
        for (ExternalOrderLineEntity l : lines) {
            inputs.add(new ExternalOrderMath.LineInput(l.getUnitPurchaseNet(), l.getQuantity(), percent(l.getPurchaseVatRate())));
        }
        BigDecimal payable = ExternalOrderMath.compute(inputs, o.getDeliveryCostNet(), percent(o.getDeliveryVatRate()))
                .payableGross();
        BigDecimal remainder = payable.subtract(nz(linkDao.sumAmountByExternalOrderId(o.getId())))
                .setScale(2, RoundingMode.HALF_UP);
        if (remainder.signum() <= 0) {
            return java.util.Optional.empty();
        }
        List<String> invoices = new ArrayList<>();
        List<String> nips = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ExternalOrderDocumentEntity d : orderDocumentDao.findByExternalOrderId(o.getId())) {
            ProcurementDocumentEntity doc = d.getProcurementDocument();
            if (doc.getInvoiceNumber() != null) {
                invoices.add(doc.getInvoiceNumber());
            }
            if (doc.getSupplierNip() != null) {
                nips.add(doc.getSupplierNip());
            }
            if (doc.getSupplierName() != null) {
                names.add(doc.getSupplierName());
            }
        }
        return java.util.Optional.of(new ExternalOrderCandidate(o.getId(), o.getProject().getId(), remainder,
                o.getSeller().getNamePL(), o.getSeller().getNip(), invoices, nips, names));
    }

    private static BigDecimal percent(VatRateEntity rate) {
        return rate == null ? null : rate.getRate();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
