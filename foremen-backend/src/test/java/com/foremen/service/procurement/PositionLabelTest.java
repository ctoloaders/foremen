package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import org.junit.jupiter.api.Test;

/** FOR-11-06 task 4.2: {@link PositionLabel} on material and pending positions. Validates: Requirements 5.3 */
class PositionLabelTest {

    @Test
    void materialLabelUsesActualOverPlannedAndAppendsProducer() {
        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setNamePL("Acme");
        producer.setNameRU("Акме");
        FinishingMaterialEntity planned = new FinishingMaterialEntity();
        planned.setModel("P-1");
        FinishingMaterialEntity actual = new FinishingMaterialEntity();
        actual.setModel("A-1");
        actual.setProducer(producer);
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setPlannedMaterial(planned);
        p.setActualMaterial(actual);

        assertThat(PositionLabel.of(p, true)).isEqualTo("A-1 (Acme)");
        assertThat(PositionLabel.of(p, false)).isEqualTo("A-1 (Акме)");
    }

    @Test
    void pendingPositionFallsBackToFinishingTypeName() {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setNamePL("Płytki");
        type.setNameRU("Плитка");
        EstimateLineRoomMaterialEntity ref = new EstimateLineRoomMaterialEntity();
        ref.setFinishingType(type);
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setEstimateReference(ref);

        assertThat(PositionLabel.of(p, true)).isEqualTo("Płytki");
        assertThat(PositionLabel.of(p, false)).isEqualTo("Плитка");
        assertThat(PositionLabel.of(new PurchasePositionEntity(), true)).isNull();
    }
}
