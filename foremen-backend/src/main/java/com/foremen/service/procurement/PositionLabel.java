package com.foremen.service.procurement;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.PurchasePositionEntity;

/**
 * FOR-11-06 (design 6.2, R5.3): display label of a purchase position. Returns the material label
 * ("model (producer)") of the actual / planned material, or the finishing type name for an
 * estimate-derived position whose material is not chosen yet. Never throws on a pending position.
 */
public final class PositionLabel {

    /** Error key raised when a pending (material-less) position reaches a step that needs a material. */
    public static final String MATERIAL_REQUIRED = "error.estimateSync.materialRequired";

    private PositionLabel() {
    }

    /** Effective material (actual overrides planned); {@code null} for a pending position. */
    public static FinishingMaterialEntity material(PurchasePositionEntity p) {
        return p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
    }

    public static String of(PurchasePositionEntity p, boolean pl) {
        FinishingMaterialEntity m = material(p);
        if (m != null) {
            String producer = m.getProducer() == null ? null
                    : pl ? m.getProducer().getNamePL() : m.getProducer().getNameRU();
            return producer == null || producer.isBlank() ? m.getModel() : m.getModel() + " (" + producer + ")";
        }
        MaterialTypeEntity type = p.getEstimateReference() == null ? null : p.getEstimateReference().getFinishingType();
        if (type != null) {
            return pl ? type.getNamePL() : type.getNameRU();
        }
        return null;
    }
}
