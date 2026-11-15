package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.controller.model.UpcomingDeliveryDto;
import com.foremen.dao.model.ShipmentEntity;

/**
 * FOR-11-04 task 3.12: nearest-shipment selection and the no-money reflection guard of
 * {@link UpcomingDeliveryDto}.
 *
 * <p>Validates Requirements 12.2, 12.4, 26.1.
 */
class UpcomingDeliveriesShipmentFieldsTest {

    private static final Pattern MONEY_LIKE =
            Pattern.compile("price|cost|amount|margin|vat|net|gross", Pattern.CASE_INSENSITIVE);

    private static ShipmentEntity shipment(long id, LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(id);
        s.setEtaDate(eta);
        return s;
    }

    @Test
    @DisplayName("DTO has no money-like component and keeps the original components first, in order")
    void dtoGuard() {
        List<String> names = Arrays.stream(UpcomingDeliveryDto.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(names).noneMatch(n -> MONEY_LIKE.matcher(n).find());
        assertThat(names.subList(0, 11)).containsExactly("positionId", "projectId", "projectName",
                "materialModel", "producer", "quantity", "unitName", "plannedDeliveryDate", "status",
                "overdue", "requestId");
    }

    @Test
    @DisplayName("nearest - earliest ETA wins, null ETA last, ties by id, none gives null")
    void nearestSelection() {
        LocalDate d = LocalDate.of(2030, 1, 10);
        assertThat(UpcomingDeliveriesService.nearest(List.of())).isNull();
        assertThat(UpcomingDeliveriesService.nearest(
                List.of(shipment(1, null), shipment(2, d.plusDays(2)), shipment(3, d))).getId()).isEqualTo(3);
        assertThat(UpcomingDeliveriesService.nearest(
                List.of(shipment(5, d), shipment(4, d))).getId()).isEqualTo(4);
        assertThat(UpcomingDeliveriesService.nearest(
                List.of(shipment(7, null), shipment(6, null))).getId()).isEqualTo(6);
    }
}
