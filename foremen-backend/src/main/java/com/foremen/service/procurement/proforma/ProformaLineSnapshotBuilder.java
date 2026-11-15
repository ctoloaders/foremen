package com.foremen.service.procurement.proforma;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.procurement.planner.PositionMoneyMath;

/**
 * FOR-11-03 (R3.4, R4.1): builds proforma lines from current position data. Used by the draft (live
 * snapshot, refreshed on every draft write) and by issue (frozen snapshot).
 *
 * <p>Item name = producer + model resolved in the document language (falls back to the material
 * name when neither is known); SKU, unit and VAT are copied; amounts come from {@link ProformaMath},
 * so the proforma gross equals the planner selection total. Only sale-side values are read.
 */
@Component
public class ProformaLineSnapshotBuilder {

    public static final String DEFAULT_LANGUAGE = "pl";
    private static final int DESCRIPTION_MAX = 500;

    /** Lines (not persisted, {@code lineNo} from 1 in input order) with their totals. */
    public record Snapshot(List<ClientProformaLineEntity> lines, PositionMoneyMath.Totals totals) {
    }

    /**
     * @param positions validated positions: non-null {@code salePrice} and {@code saleVatRate}
     * @param language  {@code pl} or {@code ru}; anything else is treated as {@code pl}
     */
    public Snapshot build(ClientProformaEntity proforma, List<PurchasePositionEntity> positions, String language) {
        boolean pl = !"ru".equalsIgnoreCase(language);
        List<ProformaMath.LineInput> inputs = new ArrayList<>(positions.size());
        for (PurchasePositionEntity p : positions) {
            inputs.add(new ProformaMath.LineInput(p.getSalePrice(), p.getQuantity(), p.getSaleVatRate().getRate()));
        }
        ProformaMath.Result math = ProformaMath.compute(inputs);

        List<ClientProformaLineEntity> lines = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            PurchasePositionEntity p = positions.get(i);
            FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
            PositionMoneyMath.Line amounts = math.lines().get(i);

            ClientProformaLineEntity line = new ClientProformaLineEntity();
            line.setProforma(proforma);
            line.setPosition(p);
            line.setLineNo(i + 1);
            line.setMaterial(m);
            line.setDescription(description(m, pl));
            line.setSku(m.getSku());
            line.setQuantity(p.getQuantity());
            line.setUnitName(pl ? p.getUnit().getNamePL() : p.getUnit().getNameRU());
            line.setUnitSaleNet(p.getSalePrice());
            line.setVatRate(p.getSaleVatRate());
            line.setVatRatePercent(p.getSaleVatRate().getRate());
            line.setNet(amounts.net());
            line.setVat(amounts.vat());
            line.setGross(amounts.gross());
            line.setActive(true);
            lines.add(line);
        }
        return new Snapshot(lines, math.totals());
    }

    static String description(FinishingMaterialEntity m, boolean pl) {
        String producer = m.getProducer() == null ? null
                : pl ? m.getProducer().getNamePL() : m.getProducer().getNameRU();
        String name = join(producer, m.getModel());
        if (name.isEmpty() && m.getMaterial() != null) {
            name = join(pl ? m.getMaterial().getNamePL() : m.getMaterial().getNameRU(), null);
        }
        if (name.isEmpty()) {
            name = "#" + m.getId();
        }
        return name.length() > DESCRIPTION_MAX ? name.substring(0, DESCRIPTION_MAX) : name;
    }

    private static String join(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + " " + right;
    }
}
