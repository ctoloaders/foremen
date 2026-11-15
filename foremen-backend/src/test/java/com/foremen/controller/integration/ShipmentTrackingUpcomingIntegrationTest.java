package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-04 task 5.2 (part 3): the shipment extension of {@code GET /api/purchase-positions/upcoming}
 * against PostgreSQL: the new nullable fields, nearest-shipment selection, the {@code shipmentId} filter,
 * the degraded flag, and a statement count that does not grow with the number of positions (batch query,
 * no N+1).
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 12.1 - 12.5.
 */
class ShipmentTrackingUpcomingIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String UPCOMING = POSITIONS + "/upcoming";

    private static LocalDate warsawToday() {
        return LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    private PurchasePositionEntity planned(ProjectEntity project, PositionStatus status, LocalDate date) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        p.setPlannedDeliveryDate(date);
        em.flush();
        return p;
    }

    private ShipmentEntity shipment(ProjectEntity project, PurchasePositionEntity position, DeliveryMethod method,
                                    ShipmentStatus status, LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setProject(project);
        s.setDeliveryMethod(method);
        s.setStatus(status);
        s.setEtaDate(eta);
        s.setShippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        em.persist(s);
        ShipmentLineEntity line = new ShipmentLineEntity();
        line.setShipment(s);
        line.setPosition(position);
        line.setQuantity(new BigDecimal("1.000"));
        em.persist(line);
        em.flush();
        return s;
    }

    @Test
    @DisplayName("nearest active shipment fills the new fields; count covers active shipments only; none -> null fields")
    void nearestShipmentAndFields() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity withShipments = planned(project, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
        PurchasePositionEntity without = planned(project, PositionStatus.ORDERED, warsawToday().plusDays(4));

        ShipmentEntity far = shipment(project, withShipments, DeliveryMethod.SHOP_DELIVERY,
                ShipmentStatus.IN_TRANSIT, warsawToday().plusDays(6));
        far.setShopOrderLink("https://shop.example.com/order/1");
        ShipmentEntity near = shipment(project, withShipments, DeliveryMethod.CARRIER,
                ShipmentStatus.OUT_FOR_DELIVERY, warsawToday().plusDays(2));
        near.setCarrierCode("OTHER");
        near.setTrackingNumber("TRK123");
        near.setTrackingUrl("https://carrier.example.com/t/TRK123");
        near.setEtaTimeFrom(LocalTime.of(9, 0));
        near.setEtaTimeTo(LocalTime.of(12, 30));
        near.setDriverName("Jan Kowalski");
        near.setDriverPhone("+48 600 100 200");
        shipment(project, withShipments, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.CANCELLED, warsawToday().plusDays(1));
        shipment(project, withShipments, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.DELIVERED, warsawToday());
        em.flush();
        em.clear();

        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].positionId").value(withShipments.getId()))
                .andExpect(jsonPath("$[0].shipmentId").value(near.getId()))
                .andExpect(jsonPath("$[0].shipmentStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$[0].deliveryMethod").value("CARRIER"))
                .andExpect(jsonPath("$[0].carrierCode").value("OTHER"))
                .andExpect(jsonPath("$[0].driverName").value("Jan Kowalski"))
                .andExpect(jsonPath("$[0].driverPhone").value("+48 600 100 200"))
                .andExpect(jsonPath("$[0].trackingPageUrl").value("https://carrier.example.com/t/TRK123"))
                .andExpect(jsonPath("$[0].shipmentCount").value(2))
                .andExpect(jsonPath("$[0].degraded").value(false))
                .andExpect(jsonPath("$[1].positionId").value(without.getId()))
                .andExpect(jsonPath("$[1].shipmentId").doesNotExist())
                .andExpect(jsonPath("$[1].shipmentStatus").doesNotExist())
                .andExpect(jsonPath("$[1].shipmentCount").doesNotExist())
                .andExpect(jsonPath("$[1].degraded").doesNotExist());
        // existing fields are unchanged
        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId())))
                .andExpect(jsonPath("$[0].status").value("IN_DELIVERY"))
                .andExpect(jsonPath("$[0].overdue").value(false))
                .andExpect(jsonPath("$[0].salePrice").doesNotExist());
    }

    @Test
    @DisplayName("shipments without an ETA lose to one with an ETA; ties fall to the lower id; five failures mean degraded")
    void tiebreakAndDegraded() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = planned(project, PositionStatus.IN_DELIVERY, warsawToday().plusDays(3));
        shipment(project, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.CREATED, null);
        ShipmentEntity first = shipment(project, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT,
                warsawToday().plusDays(3));
        shipment(project, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT, warsawToday().plusDays(3));
        first.setConsecutiveFailures(5);
        em.flush();
        em.clear();

        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shipmentId").value(first.getId()))
                .andExpect(jsonPath("$[0].shipmentCount").value(3))
                .andExpect(jsonPath("$[0].degraded").value(true));
    }

    @Test
    @DisplayName("shipmentId narrows the result to the positions of that shipment")
    void shipmentIdFilter() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = planned(project, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
        PurchasePositionEntity b = planned(project, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
        PurchasePositionEntity c = planned(project, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
        ShipmentEntity shared = shipment(project, a, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT,
                warsawToday().plusDays(2));
        ShipmentLineEntity extra = new ShipmentLineEntity();
        extra.setShipment(shared);
        extra.setPosition(b);
        extra.setQuantity(new BigDecimal("1.000"));
        em.persist(extra);
        shipment(project, c, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT, warsawToday().plusDays(2));
        em.flush();
        em.clear();

        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId()))
                        .param("shipmentId", String.valueOf(shared.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.positionId == " + a.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.positionId == " + b.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.positionId == " + c.getId() + ")]", hasSize(0)));
        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId()))
                        .param("shipmentId", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("batch query: the number of SQL statements does not grow with the number of positions")
    void noNPlusOne() throws Exception {
        ProjectEntity few = project(ProjectStatus.ACTIVE);
        ProjectEntity many = project(ProjectStatus.ACTIVE);
        for (int i = 0; i < 2; i++) {
            PurchasePositionEntity p = planned(few, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
            shipment(few, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT, warsawToday().plusDays(2 + i));
        }
        for (int i = 0; i < 8; i++) {
            PurchasePositionEntity p = planned(many, PositionStatus.IN_DELIVERY, warsawToday().plusDays(2));
            shipment(many, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.IN_TRANSIT, warsawToday().plusDays(2 + i));
            shipment(many, p, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.CREATED, null);
        }
        em.flush();
        em.clear();

        var statistics = em.unwrap(Session.class).getSessionFactory().getStatistics();
        statistics.setStatisticsEnabled(true);
        long fewCount = statementsFor(few, statistics);
        long manyCount = statementsFor(many, statistics);

        assertThat(manyCount).isEqualTo(fewCount);
    }

    private long statementsFor(ProjectEntity project, org.hibernate.stat.Statistics statistics) throws Exception {
        em.clear();
        statistics.clear();
        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }
}
