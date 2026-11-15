package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;

/**
 * FOR-11-04 task 5.2 (part 4): {@code GET /api/purchase-positions/schedule-overlay} against PostgreSQL:
 * category grouping by the manual work category, the works start from the earliest bar, the three risk
 * flags, the nearest active shipment status, the unlinked bucket, the absence of money fields, a project
 * without a schedule, and project scope (404 for a foreman who is not a member).
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 13.1 - 13.6, 17.3.
 */
class ScheduleOverlayIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String OVERLAY = POSITIONS + "/schedule-overlay";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2031, 3, 3);
    /** Half-day 3 is the second calendar day. */
    private static final LocalDate WORKS_START = START.plusDays(1);

    private static LocalDate warsawToday() {
        return LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    private WorkCategoryEntity category(String key, int order) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode("so_" + key + "_" + unique());
        c.setOrderNo(order);
        c.setNameRU("Кат " + key);
        c.setNamePL("Kat " + key);
        c.setActive(true);
        em.persist(c);
        return c;
    }

    private ProjectEntity scheduledProject(WorkCategoryEntity category, int startHalfDay) {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        project.setStartDate(START);
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setProject(project);
        ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
        bar.setSchedule(schedule);
        bar.setWorkCategory(category);
        bar.setStartHalfDays(startHalfDay);
        bar.setDurationHalfDays(4);
        schedule.getBars().add(bar);
        em.persist(schedule);
        em.flush();
        return project;
    }

    private PurchasePositionEntity pos(ProjectEntity project, PositionStatus status, WorkCategoryEntity category,
                                       LocalDate arrival, LocalDate orderBy) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        p.setWorkCategory(category);
        p.setPlannedDeliveryDate(arrival);
        p.setOrderByDate(orderBy);
        em.flush();
        return p;
    }

    private ShipmentEntity shipment(ProjectEntity project, PurchasePositionEntity position, ShipmentStatus status,
                                    LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setProject(project);
        s.setDeliveryMethod(DeliveryMethod.PHONE_CONTACT);
        s.setDriverPhone("+48 600 100 200");
        s.setStatus(status);
        s.setEtaDate(eta);
        s.setShippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        em.persist(s);
        ShipmentLineEntity line = new ShipmentLineEntity();
        line.setShipment(s);
        line.setPosition(position);
        line.setQuantity(new java.math.BigDecimal("1.000"));
        em.persist(line);
        em.flush();
        return s;
    }

    private JsonNode overlay(ProjectEntity project, org.springframework.test.web.servlet.request.RequestPostProcessor as)
            throws Exception {
        var req = get(OVERLAY).param("projectId", String.valueOf(project.getId()));
        if (as != null) {
            req = req.with(as);
        }
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static JsonNode byId(JsonNode array, Long positionId) {
        for (JsonNode n : array) {
            if (n.get("positionId").asLong() == positionId) {
                return n;
            }
        }
        throw new AssertionError("position " + positionId + " not in " + array);
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isArray()
                ? LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt())
                : LocalDate.parse(node.asText());
    }

    @Test
    @DisplayName("groups by category with the works start, derives the three flags and the shipment status")
    void overlay_categoriesAndFlags() throws Exception {
        WorkCategoryEntity category = category("A", 1);
        ProjectEntity project = scheduledProject(category, 3);
        PurchasePositionEntity risky = pos(project, PositionStatus.IN_DELIVERY, category,
                WORKS_START.plusDays(3), null);
        PurchasePositionEntity onTime = pos(project, PositionStatus.ORDERED, category, WORKS_START, null);
        PurchasePositionEntity missedOrderBy = pos(project, PositionStatus.PLANNED, category, null,
                warsawToday().minusDays(1));
        PurchasePositionEntity received = pos(project, PositionStatus.RECEIVED, category,
                WORKS_START.plusDays(9), null);
        PurchasePositionEntity cancelled = pos(project, PositionStatus.CANCELLED, category, null, null);
        PurchasePositionEntity unlinked = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        shipment(project, risky, ShipmentStatus.IN_TRANSIT, WORKS_START.plusDays(3));
        shipment(project, risky, ShipmentStatus.OUT_FOR_DELIVERY, WORKS_START.plusDays(5));
        shipment(project, onTime, ShipmentStatus.DELIVERED, WORKS_START);
        em.clear();

        JsonNode body = overlay(project, null);

        assertThat(body.get("projectId").asLong()).isEqualTo(project.getId());
        assertThat(date(body.get("anchorDate"))).isEqualTo(START);
        assertThat(body.get("categories")).hasSize(1);
        JsonNode cat = body.get("categories").get(0);
        assertThat(cat.get("workCategoryId").asLong()).isEqualTo(category.getId());
        assertThat(date(cat.get("worksStartDate"))).isEqualTo(WORKS_START);
        JsonNode positions = cat.get("positions");
        assertThat(positions).hasSize(4); // cancelled is omitted

        JsonNode r = byId(positions, risky.getId());
        assertThat(r.get("downtimeRisk").asBoolean()).isTrue();
        assertThat(r.get("orderByMissed").asBoolean()).isFalse();
        assertThat(r.get("noArrivalDate").asBoolean()).isFalse();
        assertThat(r.get("shipmentStatus").asText()).isEqualTo("IN_TRANSIT"); // nearest active shipment
        assertThat(date(r.get("arrivalDate"))).isEqualTo(WORKS_START.plusDays(3));

        JsonNode ok = byId(positions, onTime.getId());
        assertThat(ok.get("downtimeRisk").asBoolean()).isFalse();
        assertThat(ok.get("shipmentStatus").isNull()).isTrue(); // only a DELIVERED shipment

        JsonNode m = byId(positions, missedOrderBy.getId());
        assertThat(m.get("orderByMissed").asBoolean()).isTrue();
        assertThat(m.get("noArrivalDate").asBoolean()).isTrue();
        assertThat(m.get("downtimeRisk").asBoolean()).isFalse();

        JsonNode rc = byId(positions, received.getId());
        assertThat(rc.get("downtimeRisk").asBoolean()).isFalse();
        assertThat(rc.get("noArrivalDate").asBoolean()).isFalse();

        assertThat(body.get("unlinked")).hasSize(1);
        assertThat(body.get("unlinked").get(0).get("positionId").asLong()).isEqualTo(unlinked.getId());
        assertThat(body.get("unlinked").get(0).get("noArrivalDate").asBoolean()).isTrue();
        assertThat(cancelled.getId()).isNotNull();
    }

    @Test
    @DisplayName("the response has no money fields")
    void overlay_noMoney() throws Exception {
        WorkCategoryEntity category = category("M", 1);
        ProjectEntity project = scheduledProject(category, 1);
        pos(project, PositionStatus.ORDERED, category, WORKS_START, null);
        em.clear();

        String body = mockMvc.perform(get(OVERLAY).param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("price").doesNotContainIgnoringCase("cost")
                .doesNotContainIgnoringCase("vat").doesNotContainIgnoringCase("margin")
                .doesNotContainIgnoringCase("amount");
    }

    @Test
    @DisplayName("a project without a schedule answers 200 with empty categories; positions fall into unlinked")
    void overlay_noSchedule() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        em.clear();

        mockMvc.perform(get(OVERLAY).param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.unlinked", hasSize(1)))
                .andExpect(jsonPath("$.unlinked[0].positionId").value(p.getId()));
    }

    @Test
    @DisplayName("project scope: a member foreman reads the overlay, a non-member gets 404, a missing projectId 400")
    void overlay_scope() throws Exception {
        WorkCategoryEntity category = category("S", 1);
        ProjectEntity mine = scheduledProject(category, 1);
        ProjectEntity other = scheduledProject(category, 1);
        UserEntity foreman = memberUser("FOREMAN", mine);
        pos(mine, PositionStatus.ORDERED, category, WORKS_START, null);
        em.clear();

        mockMvc.perform(get(OVERLAY).param("projectId", String.valueOf(mine.getId())).with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(1)));
        mockMvc.perform(get(OVERLAY).param("projectId", String.valueOf(other.getId())).with(as(foreman, "FOREMAN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(OVERLAY)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ABAC: a CLIENT or WORKER without a PURCHASE_POSITIONS grant gets 403")
    void overlay_abac() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity worker = memberUser("WORKER", project);
        mockMvc.perform(get(OVERLAY).param("projectId", String.valueOf(project.getId()))
                .with(as(worker, "WORKER"))).andExpect(status().isForbidden());
    }
}
