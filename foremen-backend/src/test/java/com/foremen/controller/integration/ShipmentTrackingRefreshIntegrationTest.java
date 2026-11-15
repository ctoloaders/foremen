package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.EtaSource;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentEventEntity;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;

/**
 * FOR-11-04 task 5.2 (part 1): {@code POST /api/shipments/{id}/refresh} with the {@code STUB} adapter
 * against PostgreSQL: result application, cooldown {@code 429}, failure back-off, degraded flag, carrier
 * pause, terminal and manual-ETA rules, and the immediate first refresh after create.
 *
 * <p>Repeatability: committed data, wiped per test ({@link ShipmentTrackingCommittedIntegrationBase});
 * tracking numbers carry the run's nano-time. No carrier is called.
 *
 * <p>Validates Requirements 4.8, 5.3, 5.4, 7.6 - 7.9, 10.1 - 10.5, 11.1.
 */
class ShipmentTrackingRefreshIntegrationTest extends ShipmentTrackingCommittedIntegrationBase {

    private static String refreshUrl(Long id) {
        return "/api/shipments/" + id + "/refresh";
    }

    private static String number(String prefix) {
        return prefix + System.nanoTime();
    }

    @Test
    @DisplayName("refresh UPDATED: status, ETA, event, next check and the position planned date")
    void refresh_appliesResult() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long id = newStubShipment(project, position, number("STUBTRANSIT"), ShipmentStatus.CREATED, null);

        mockMvc.perform(post(refreshUrl(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.statusSource").value("TRACKING"));

        ShipmentEntity s = reload(id);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(s.getEtaDate()).isEqualTo(warsawToday().plusDays(2));
        assertThat(s.getEtaSource()).isEqualTo(EtaSource.TRACKING);
        assertThat(s.getConsecutiveFailures()).isZero();
        assertThat(s.getLastCheckedAt()).isNotNull();
        assertThat(Duration.between(s.getLastCheckedAt(), s.getNextCheckAt()))
                .isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
        List<ShipmentEventEntity> events = eventDao.findByShipmentId(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(events.get(0).getSource()).isEqualTo(ShipmentSource.TRACKING);
        assertThat(position(position).getPlannedDeliveryDate()).isEqualTo(warsawToday().plusDays(2));
    }

    @Test
    @DisplayName("a later ETA from the carrier moves the position planned date; a manual ETA is never overwritten")
    void refresh_etaRules() throws Exception {
        Long project = newProject();
        Long late = newPosition(project, PositionStatus.IN_DELIVERY);
        Long manualPos = newPosition(project, PositionStatus.IN_DELIVERY);
        Long lateShipment = newStubShipment(project, late, number("STUBLATE"), ShipmentStatus.IN_TRANSIT, null);
        Long manual = newStubShipment(project, manualPos, number("STUBTRANSIT"), ShipmentStatus.CREATED, null);
        java.time.LocalDate manualEta = warsawToday().plusDays(20);
        tx().executeWithoutResult(t -> {
            ShipmentEntity m = shipmentDao.findById(manual).orElseThrow();
            m.setEtaDate(manualEta);
            m.setEtaSource(EtaSource.MANUAL);
            shipmentDao.save(m);
        });

        mockMvc.perform(post(refreshUrl(lateShipment))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("UPDATED"));
        assertThat(reload(lateShipment).getEtaDate()).isEqualTo(warsawToday().plusDays(9));
        assertThat(position(late).getPlannedDeliveryDate()).isEqualTo(warsawToday().plusDays(9));

        mockMvc.perform(post(refreshUrl(manual))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        ShipmentEntity m = reload(manual);
        assertThat(m.getEtaDate()).isEqualTo(manualEta);
        assertThat(m.getEtaSource()).isEqualTo(EtaSource.MANUAL);
        assertThat(position(manualPos).getPlannedDeliveryDate()).isEqualTo(manualEta);
    }

    @Test
    @DisplayName("an immediate second refresh answers 429 with Retry-After and retryAfterSeconds; no new call is made")
    void refresh_cooldown() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long id = newStubShipment(project, position, number("STUBTRANSIT"), ShipmentStatus.CREATED, null);
        mockMvc.perform(post(refreshUrl(id))).andExpect(status().isOk());
        OffsetDateTime firstCheck = reload(id).getLastCheckedAt();

        mockMvc.perform(post(refreshUrl(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.params.retryAfterSeconds", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.params.retryAfterSeconds", lessThanOrEqualTo(60)));
        assertThat(reload(id).getLastCheckedAt()).isEqualTo(firstCheck);

        ageLastCheck(id, 61);
        mockMvc.perform(post(refreshUrl(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("UNCHANGED"));
    }

    @Test
    @DisplayName("carrier failure: 200 with refreshResult FAILED, status kept, back-off 1h, 2h, 4h ..., degraded from 5")
    void refresh_failureBackoff() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long id = newStubShipment(project, position, number("STUBFAIL"), ShipmentStatus.IN_TRANSIT, null);
        long[] expectedHours = {1, 2, 4, 8, 16};

        for (int i = 0; i < expectedHours.length; i++) {
            mockMvc.perform(post(refreshUrl(id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refreshResult").value("FAILED"))
                    .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                    .andExpect(jsonPath("$.degraded").value(i + 1 >= 5));
            ShipmentEntity s = reload(id);
            assertThat(s.getConsecutiveFailures()).isEqualTo(i + 1);
            assertThat(Duration.between(s.getLastCheckedAt(), s.getNextCheckAt()))
                    .isBetween(Duration.ofHours(expectedHours[i]).minusSeconds(5),
                            Duration.ofHours(expectedHours[i]).plusSeconds(5));
            ageLastCheck(id, 61);
        }
        assertThat(eventDao.findByShipmentId(id)).isEmpty();
    }

    @Test
    @DisplayName("BLOCKED pauses the carrier: the next manual refresh of any STUB shipment answers 409 carrierPaused")
    void refresh_carrierPause() throws Exception {
        Long project = newProject();
        Long blocked = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBBLOCK"), ShipmentStatus.CREATED, null);
        Long other = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBTRANSIT"), ShipmentStatus.CREATED, null);

        mockMvc.perform(post(refreshUrl(blocked))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("FAILED"));
        assertThat(throttle.isPaused("STUB")).isTrue();

        mockMvc.perform(post(refreshUrl(other)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(reload(other).getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(reload(other).getLastCheckedAt()).isNull();
    }

    @Test
    @DisplayName("DELIVERED from the carrier stamps deliveredAt, stops scheduling, leaves the position IN_DELIVERY")
    void refresh_delivered() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long id = newStubShipment(project, position, number("STUBDELIV"), ShipmentStatus.IN_TRANSIT, null);

        mockMvc.perform(post(refreshUrl(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshResult").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        ShipmentEntity s = reload(id);
        assertThat(s.getDeliveredAt()).isNotNull();
        assertThat(s.getNextCheckAt()).isNull();
        assertThat(position(position).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);

        ageLastCheck(id, 61);
        mockMvc.perform(post(refreshUrl(id))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a manual status is overwritten by the carrier only on a forward move")
    void refresh_manualStatusNotRegressed() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long id = newStubShipment(project, position, number("STUBTRANSIT"), ShipmentStatus.OUT_FOR_DELIVERY, null);

        mockMvc.perform(post(refreshUrl(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.statusSource").value("MANUAL"));
        // Only the ETA was new: the status stayed and no TRACKING event was written.
        assertThat(reload(id).getEtaDate()).isEqualTo(warsawToday().plusDays(2));
        assertThat(eventDao.findByShipmentId(id)).isEmpty();
    }

    @Test
    @DisplayName("refresh is not available for shop delivery or an unknown carrier: 409 notAvailable, no call")
    void refresh_notAvailable() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.IN_DELIVERY);
        Long shop = newStubShipment(project, position, null, ShipmentStatus.CREATED, null);
        Long unknown = newStubShipment(project, null, number("ZZ"), ShipmentStatus.CREATED, null);
        tx().executeWithoutResult(t -> {
            ShipmentEntity s = shipmentDao.findById(shop).orElseThrow();
            s.setDeliveryMethod(DeliveryMethod.SHOP_DELIVERY);
            s.setCarrierCode(null);
            shipmentDao.save(s);
            ShipmentEntity u = shipmentDao.findById(unknown).orElseThrow();
            u.setCarrierCode("NOPE");
            shipmentDao.save(u);
        });

        mockMvc.perform(post(refreshUrl(shop))).andExpect(status().isConflict());
        mockMvc.perform(post(refreshUrl(unknown))).andExpect(status().isConflict());
        mockMvc.perform(post(refreshUrl(-1L))).andExpect(status().isNotFound());
        assertThat(reload(shop).getLastCheckedAt()).isNull();
        assertThat(reload(unknown).getLastCheckedAt()).isNull();
    }

    @Test
    @DisplayName("create with a trackable carrier runs the first refresh right after commit")
    void create_runsFirstRefresh() throws Exception {
        Long project = newProject();
        Long position = newPosition(project, PositionStatus.SUPPLIER_PAID);
        String body = """
                {"projectId": %d, "deliveryMethod": "CARRIER", "carrierCode": "STUB",
                 "trackingNumber": "%s", "lines": [{"positionId": %d, "quantity": 2}]}
                """.formatted(project, number("STUBTRANSIT"), position);

        String response = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asLong();

        ShipmentEntity s = reload(id);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(s.getEtaDate()).isEqualTo(warsawToday().plusDays(2));
        assertThat(s.getLastCheckedAt()).isNotNull();
        assertThat(position(position).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(position(position).getPlannedDeliveryDate()).isEqualTo(warsawToday().plusDays(2));
    }

}
