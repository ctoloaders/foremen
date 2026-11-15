package com.foremen.service.procurement.receipt;

import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Inputs;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Ledger;
import com.foremen.service.procurement.shipment.ShipmentRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FOR-11-05 (R3.1, R3.2, R3.6, R8.1): read model of the quantity ledger shared by the receipt service, the
 * ledger endpoint and {@code ShipmentService}. Quantities only; no money is read or returned.
 */
@Service
public class GoodsReceiptLedgerService {

    /** No shipment has this id, so excluding it keeps every shipment line. */
    private static final Long NO_EXCLUSION = -1L;

    private final PurchasePositionDao positionDao;
    private final GoodsReceiptDao receiptDao;
    private final ShipmentLineDao shipmentLineDao;

    public GoodsReceiptLedgerService(PurchasePositionDao positionDao, GoodsReceiptDao receiptDao,
                                     ShipmentLineDao shipmentLineDao) {
        this.positionDao = positionDao;
        this.receiptDao = receiptDao;
        this.shipmentLineDao = shipmentLineDao;
    }

    /** Ledger per existing position id, computed with three queries (positions, event sums, shipped sums). */
    @Transactional(readOnly = true)
    public Map<Long, Ledger> ledgers(Collection<Long> positionIds) {
        return compute(positionIds, NO_EXCLUSION);
    }

    /**
     * {@code committed} per position with the lines of {@code excludeShipmentId} left out of the shipped sum
     * (used when a shipment is edited). Without events this equals the FOR-11-04 shipped sum.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> committed(Collection<Long> positionIds, Long excludeShipmentId) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        compute(positionIds, excludeShipmentId == null ? NO_EXCLUSION : excludeShipmentId)
                .forEach((id, l) -> result.put(id, l.committed()));
        return result;
    }

    /** MISSING plus door RETURNED units per position on shipments of one external order (R8.5). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> releasedByOrder(Long orderId, Collection<Long> positionIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (orderId == null || positionIds == null || positionIds.isEmpty()) {
            return result;
        }
        for (Object[] row : receiptDao.releasedByOrder(orderId, positionIds)) {
            result.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }
        return result;
    }

    private Map<Long, Ledger> compute(Collection<Long> positionIds, Long excludeShipmentId) {
        Map<Long, Ledger> result = new LinkedHashMap<>();
        if (positionIds == null || positionIds.isEmpty()) {
            return result;
        }
        Iterable<PurchasePositionEntity> positions = positionDao.findAllById(positionIds);

        Map<Long, Map<String, BigDecimal>> events = new HashMap<>();
        for (Object[] row : receiptDao.sumsByPositionAndKind(positionIds)) {
            Long positionId = ((Number) row[0]).longValue();
            ReceiptKind kind = (ReceiptKind) row[1];
            boolean fromAccepted = Boolean.TRUE.equals(row[2]);
            events.computeIfAbsent(positionId, k -> new HashMap<>())
                    .merge(bucket(kind, fromAccepted), (BigDecimal) row[3], BigDecimal::add);
        }

        Map<Long, BigDecimal> shipped = new HashMap<>();
        for (Object[] row : shipmentLineDao.sumActiveQuantityByPositionExcluding(positionIds, excludeShipmentId)) {
            shipped.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
        }

        for (PurchasePositionEntity p : positions) {
            BigDecimal cap = ShipmentRules.capFor(p.getSourcingPath(), p.getOrderedQuantity(), p.getQuantity());
            Map<String, BigDecimal> e = events.getOrDefault(p.getId(), Map.of());
            BigDecimal shippedGross = shipped.get(p.getId());
            result.put(p.getId(), GoodsReceiptRules.ledger(new Inputs(
                    cap == null ? BigDecimal.ZERO : cap,
                    shippedGross != null,
                    shippedGross == null ? BigDecimal.ZERO : shippedGross,
                    e.getOrDefault("ACCEPTED", BigDecimal.ZERO),
                    e.getOrDefault("MISSING", BigDecimal.ZERO),
                    e.getOrDefault("RETURNED_DOOR", BigDecimal.ZERO),
                    e.getOrDefault("RETURNED_ACCEPTED", BigDecimal.ZERO))));
        }
        return result;
    }

    private static String bucket(ReceiptKind kind, boolean fromAccepted) {
        if (kind == ReceiptKind.RETURNED) {
            return fromAccepted ? "RETURNED_ACCEPTED" : "RETURNED_DOOR";
        }
        return kind.name();
    }
}
