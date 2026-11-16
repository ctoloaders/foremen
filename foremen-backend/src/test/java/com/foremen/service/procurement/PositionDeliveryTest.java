package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.RequestStatus;
import com.foremen.service.procurement.PurchaseRequestStatusDeriver.Stage;

/** FOR-11-06 task 7.3: {@code Delivery_Complete} = RECEIVED and not client-unpaid (R10.4). */
class PositionDeliveryTest {

    @Test
    void completeOnlyWhenReceivedAndPaid() {
        for (PositionStatus s : PositionStatus.values()) {
            assertThat(PositionDelivery.isComplete(s, false)).isEqualTo(s == PositionStatus.RECEIVED);
            assertThat(PositionDelivery.isComplete(s, true)).isFalse();
        }
    }

    @Test
    void requestWithReceivedUnpaidPositionIsNotCompleted() {
        assertThat(PurchaseRequestStatusDeriver.deriveStages(List.of(
                new Stage(PositionStatus.RECEIVED, false), new Stage(PositionStatus.CANCELLED, false))))
                .isEqualTo(RequestStatus.COMPLETED);
        assertThat(PurchaseRequestStatusDeriver.deriveStages(List.of(
                new Stage(PositionStatus.RECEIVED, false), new Stage(PositionStatus.RECEIVED, true))))
                .isEqualTo(RequestStatus.RELEASED);
    }
}
