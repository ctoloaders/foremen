package com.foremen.service.model.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.service.model.ExternalOrderProformaRefServiceModel;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.bank.link.DocumentPaymentStatusDeriver;
import com.foremen.service.model.ExternalOrderDocumentServiceModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.ExternalOrderServiceModel;
import com.foremen.service.procurement.externalorder.CeilingCheck;
import com.foremen.service.procurement.externalorder.ExternalOrderCoverageValidator;
import com.foremen.service.procurement.externalorder.ExternalOrderMath;
import com.foremen.service.procurement.externalorder.ExternalOrderPaymentDeriver;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.planner.PositionMoneyMath;

import jakarta.persistence.EntityManager;

/**
 * Service mapper of external orders (FOR-11-03 R11, R12, R14). Hand-written. Both read models carry the
 * derived totals of {@link ExternalOrderMath}; the extended model also carries lines, documents, the
 * ledger-paid amount and the documents-paid flag. {@link #updateFields} is null-ignore and touches only
 * header fields: project and status are system-owned, lines and documents have their own operations.
 *
 * <p>Money visibility is not decided here: the controller re-checks the caller's role.
 */
@Component
public class ExternalOrderServiceMapper implements ServiceToDaoMapper<ExternalOrderEntity,
        ExternalOrderServiceModel, ExternalOrderServiceExtendedModel> {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final EntityManager entityManager;
    private final ExternalOrderLineDao lineDao;
    private final ExternalOrderDocumentDao documentDao;
    private final BankTransactionLinkDao linkDao;
    private final ClientProformaLineDao proformaLineDao;

    public ExternalOrderServiceMapper(EntityManager entityManager, ExternalOrderLineDao lineDao,
                                      ExternalOrderDocumentDao documentDao, BankTransactionLinkDao linkDao,
                                      ClientProformaLineDao proformaLineDao) {
        this.proformaLineDao = proformaLineDao;
        this.entityManager = entityManager;
        this.lineDao = lineDao;
        this.documentDao = documentDao;
        this.linkDao = linkDao;
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public ExternalOrderServiceModel toServiceModel(ExternalOrderEntity source) {
        if (source == null) {
            return null;
        }
        ExternalOrderServiceModel target = new ExternalOrderServiceModel();
        fillHeader(source, target);
        fillTotals(source, target, loadLines(source));
        return target;
    }

    /**
     * List variant (R21.3): totals of every order of the page from ONE lines query instead of one query
     * per row. Same output as {@link #toServiceModel} for each entity.
     */
    public List<ExternalOrderServiceModel> toServiceModels(List<ExternalOrderEntity> sources) {
        List<Long> ids = sources.stream().filter(s -> s != null && s.getId() != null)
                .map(ExternalOrderEntity::getId).toList();
        Map<Long, List<ExternalOrderLineEntity>> byOrder = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ExternalOrderLineEntity l : lineDao.findForTotalsByExternalOrderIds(ids)) {
                byOrder.computeIfAbsent(l.getExternalOrder().getId(), k -> new ArrayList<>()).add(l);
            }
        }
        List<ExternalOrderServiceModel> result = new ArrayList<>(sources.size());
        for (ExternalOrderEntity s : sources) {
            if (s == null) {
                result.add(null);
                continue;
            }
            ExternalOrderServiceModel target = new ExternalOrderServiceModel();
            fillHeader(s, target);
            fillTotals(s, target, byOrder.getOrDefault(s.getId(), List.of()));
            result.add(target);
        }
        return result;
    }

    @Override
    public ExternalOrderServiceExtendedModel toServiceExtendedModel(ExternalOrderEntity source) {
        if (source == null) {
            return null;
        }
        ExternalOrderServiceExtendedModel target = new ExternalOrderServiceExtendedModel();
        fillHeader(source, target);
        List<ExternalOrderLineEntity> lines = loadLines(source);
        fillTotals(source, target, lines);
        target.setLines(toLines(source, lines));

        List<ExternalOrderDocumentEntity> documents = source.getId() == null
                ? List.of() : documentDao.findByExternalOrderId(source.getId());
        List<ExternalOrderDocumentServiceModel> documentModels = new ArrayList<>(documents.size());
        List<DocumentPaymentStatus> statuses = new ArrayList<>(documents.size());
        for (ExternalOrderDocumentEntity d : documents) {
            ExternalOrderDocumentServiceModel m = toDocument(source, d);
            documentModels.add(m);
            statuses.add(m.getPaymentStatus());
        }
        target.setDocuments(documentModels);
        target.setDocumentsPaid(ExternalOrderPaymentDeriver.derive(BigDecimal.ZERO, BigDecimal.ZERO, statuses));
        BigDecimal ledgerPaid = source.getId() == null ? null : linkDao.sumAmountByExternalOrderId(source.getId());
        target.setLedgerPaidGross(ledgerPaid == null ? BigDecimal.ZERO.setScale(2) : ledgerPaid);
        target.setProformas(toProformaRefs(lines));
        return target;
    }

    /** R24.5: distinct proformas of the order's positions through ACTIVE lines, one batch query. */
    private List<ExternalOrderProformaRefServiceModel> toProformaRefs(List<ExternalOrderLineEntity> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Long> positionIds = lines.stream().map(l -> l.getPosition().getId()).distinct().toList();
        Map<Long, ExternalOrderProformaRefServiceModel> byProforma = new LinkedHashMap<>();
        for (ClientProformaLineEntity pl : proformaLineDao.findActiveWithProformaByPositionIds(positionIds)) {
            ClientProformaEntity p = pl.getProforma();
            byProforma.putIfAbsent(p.getId(),
                    new ExternalOrderProformaRefServiceModel(p.getId(), p.getNumber(), p.getStatus()));
        }
        return List.copyOf(byProforma.values());
    }

    private List<ExternalOrderLineEntity> loadLines(ExternalOrderEntity source) {
        return source.getId() == null ? List.of() : lineDao.findByExternalOrderId(source.getId());
    }

    private static void fillHeader(ExternalOrderEntity s, ExternalOrderServiceModel t) {
        t.setId(s.getId());
        if (s.getProject() != null) {
            t.setProjectId(s.getProject().getId());
            t.setProjectName(s.getProject().getName());
        }
        t.setPurchaseRequestId(s.getPurchaseRequest() == null ? null : s.getPurchaseRequest().getId());
        if (s.getSeller() != null) {
            t.setSellerId(s.getSeller().getId());
            t.setSellerNameRU(s.getSeller().getNameRU());
            t.setSellerNamePL(s.getSeller().getNamePL());
        }
        t.setStatus(s.getStatus());
        t.setReferenceNumber(s.getReferenceNumber());
        t.setOrderLink(s.getOrderLink());
        t.setDeliveryCostNet(s.getDeliveryCostNet());
        t.setDeliveryVatRateId(s.getDeliveryVatRate() == null ? null : s.getDeliveryVatRate().getId());
        t.setExpectedShipDate(s.getExpectedShipDate());
        t.setExpectedDeliveryDate(s.getExpectedDeliveryDate());
        UserEntity buyer = s.getAssignedBuyer();
        t.setAssignedBuyerId(buyer == null ? null : buyer.getId());
        t.setAssignedBuyerName(buyer == null ? null : buyer.getName());
        t.setNote(s.getNote());
        t.setReturnReason(s.getReturnReason());
        t.setSentAt(s.getSentAt());
        t.setPaidAt(s.getPaidAt());
        t.setCancelledAt(s.getCancelledAt());
        t.setPaidSource(s.getPaidSource());
        t.setPaidById(s.getPaidBy() == null ? null : s.getPaidBy().getId());
        t.setPaidNote(s.getPaidNote());
        t.setOverdue(isOverdue(s));
    }

    /** R11.5: {@code expectedDeliveryDate < Today} and the order is not cancelled. */
    static boolean isOverdue(ExternalOrderEntity s) {
        return s.getExpectedDeliveryDate() != null
                && s.getStatus() != ExternalOrderStatus.CANCELLED
                && s.getExpectedDeliveryDate().isBefore(LocalDate.now(WARSAW));
    }

    private static void fillTotals(ExternalOrderEntity s, ExternalOrderServiceModel t,
                                   List<ExternalOrderLineEntity> lines) {
        List<ExternalOrderMath.LineInput> inputs = new ArrayList<>(lines.size());
        for (ExternalOrderLineEntity l : lines) {
            inputs.add(new ExternalOrderMath.LineInput(l.getUnitPurchaseNet(), l.getQuantity(), percent(l.getPurchaseVatRate())));
        }
        ExternalOrderMath.Result r = ExternalOrderMath.compute(inputs, s.getDeliveryCostNet(), percent(s.getDeliveryVatRate()));
        t.setLineCount(lines.size());
        t.setLinesNet(r.linesNet());
        t.setLinesVat(r.linesVat());
        t.setLinesGross(r.linesGross());
        t.setDeliveryGross(r.deliveryGross());
        t.setPayableGross(r.payableGross());
    }

    private static BigDecimal percent(VatRateEntity rate) {
        return rate == null ? null : rate.getRate();
    }

    private List<ExternalOrderLineServiceModel> toLines(ExternalOrderEntity order, List<ExternalOrderLineEntity> lines) {
        Map<Long, BigDecimal> otherCoverage = new HashMap<>();
        if (order.getId() != null && !lines.isEmpty()) {
            List<Long> positionIds = lines.stream().map(l -> l.getPosition().getId()).toList();
            for (Object[] row : lineDao.sumQuantityByPositionExcluding(positionIds, order.getId())) {
                otherCoverage.put((Long) row[0], (BigDecimal) row[1]);
            }
        }
        List<ExternalOrderLineServiceModel> result = new ArrayList<>(lines.size());
        for (ExternalOrderLineEntity l : lines) {
            PurchasePositionEntity p = l.getPosition();
            ExternalOrderLineServiceModel m = new ExternalOrderLineServiceModel();
            m.setId(l.getId());
            m.setPositionId(p.getId());
            m.setQuantity(l.getQuantity());
            m.setUnitPurchaseNet(l.getUnitPurchaseNet());
            m.setPurchaseVatRateId(l.getPurchaseVatRate() == null ? null : l.getPurchaseVatRate().getId());
            m.setPurchaseVatRatePercent(percent(l.getPurchaseVatRate()));
            PositionMoneyMath.Line amounts = PositionMoneyMath.saleLine(
                    l.getUnitPurchaseNet(), l.getQuantity(), percent(l.getPurchaseVatRate()));
            m.setNet(amounts.net());
            m.setVat(amounts.vat());
            m.setGross(amounts.gross());
            m.setDeliveryCostShare(l.getDeliveryCostShare());
            m.setAboveCeilingAcknowledged(l.isAboveCeilingAcknowledged());
            m.setSalePrice(p.getSalePrice());
            m.setPositionQuantity(p.getQuantity());
            m.setRemainingQuantity(ExternalOrderCoverageValidator.remaining(
                    p.getQuantity(), otherCoverage.get(p.getId())));
            m.setAboveCeiling(CeilingCheck.exceeds(l.getUnitPurchaseNet(), p.getSalePrice()));
            result.add(m);
        }
        return result;
    }

    private ExternalOrderDocumentServiceModel toDocument(ExternalOrderEntity order, ExternalOrderDocumentEntity d) {
        ProcurementDocumentEntity doc = d.getProcurementDocument();
        ExternalOrderDocumentServiceModel m = new ExternalOrderDocumentServiceModel();
        m.setId(d.getId());
        m.setProcurementDocumentId(doc.getId());
        m.setAmount(d.getAmount());
        m.setInvoiceNumber(doc.getInvoiceNumber());
        m.setSupplierName(doc.getSupplierName());
        m.setSupplierNip(doc.getSupplierNip());
        m.setDocumentTotal(doc.getDocumentTotal());
        BigDecimal linked = linkDao.sumAmountByProcurementDocumentId(doc.getId());
        List<BigDecimal> amounts = linked != null && linked.signum() > 0 ? List.of(linked) : List.of();
        m.setPaymentStatus(DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(), amounts));
        MaterialSellerEntity seller = order.getSeller();
        m.setSellerMismatch(seller != null && nipsDiffer(doc.getSupplierNip(), seller.getNip()));
        return m;
    }

    /** R14.4: true when both NIPs are present (digits only) and different. */
    public static boolean nipsDiffer(String documentNip, String sellerNip) {
        String a = digits(documentNip);
        String b = digits(sellerNip);
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    private static String digits(String nip) {
        return nip == null ? "" : nip.replaceAll("\\D", "");
    }

    @Override
    public ExternalOrderEntity toCreateDaoModel(ExternalOrderServiceExtendedModel s) {
        ExternalOrderEntity e = new ExternalOrderEntity();
        e.setProject(entityManager.getReference(ProjectEntity.class, s.getProjectId()));
        e.setSeller(entityManager.getReference(MaterialSellerEntity.class, s.getSellerId()));
        e.setPurchaseRequest(s.getPurchaseRequestId() == null ? null
                : entityManager.getReference(PurchaseRequestEntity.class, s.getPurchaseRequestId()));
        e.setStatus(ExternalOrderStatus.DRAFT);
        e.setReferenceNumber(blankToNull(s.getReferenceNumber()));
        e.setOrderLink(blankToNull(s.getOrderLink()));
        e.setDeliveryCostNet(s.getDeliveryCostNet() == null ? BigDecimal.ZERO.setScale(2) : s.getDeliveryCostNet());
        e.setDeliveryVatRate(s.getDeliveryVatRateId() == null ? null
                : entityManager.getReference(VatRateEntity.class, s.getDeliveryVatRateId()));
        e.setExpectedShipDate(s.getExpectedShipDate());
        e.setExpectedDeliveryDate(s.getExpectedDeliveryDate());
        e.setAssignedBuyer(s.getAssignedBuyerId() == null ? null
                : entityManager.getReference(UserEntity.class, s.getAssignedBuyerId()));
        e.setNote(blankToNull(s.getNote()));
        return e;
    }

    @Override
    public void updateFields(ExternalOrderServiceExtendedModel s, ExternalOrderEntity t) {
        if (s.getSellerId() != null) {
            t.setSeller(entityManager.getReference(MaterialSellerEntity.class, s.getSellerId()));
        }
        if (s.getPurchaseRequestId() != null) {
            t.setPurchaseRequest(entityManager.getReference(PurchaseRequestEntity.class, s.getPurchaseRequestId()));
        }
        if (s.getReferenceNumber() != null) {
            t.setReferenceNumber(blankToNull(s.getReferenceNumber()));
        }
        if (s.getOrderLink() != null) {
            t.setOrderLink(blankToNull(s.getOrderLink()));
        }
        if (s.getDeliveryCostNet() != null) {
            t.setDeliveryCostNet(s.getDeliveryCostNet());
        }
        if (s.getDeliveryVatRateId() != null) {
            t.setDeliveryVatRate(entityManager.getReference(VatRateEntity.class, s.getDeliveryVatRateId()));
        }
        if (s.getExpectedShipDate() != null) {
            t.setExpectedShipDate(s.getExpectedShipDate());
        }
        if (s.getExpectedDeliveryDate() != null) {
            t.setExpectedDeliveryDate(s.getExpectedDeliveryDate());
        }
        if (s.getAssignedBuyerId() != null) {
            t.setAssignedBuyer(entityManager.getReference(UserEntity.class, s.getAssignedBuyerId()));
        }
        if (s.getNote() != null) {
            t.setNote(blankToNull(s.getNote()));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
