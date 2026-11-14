package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FOR-11-01 task 6.4 - {@code GET /api/purchase-positions/board}: six fixed columns, card order,
 * limit / hasMore, hideDelivered count-only, gate exclusion, ceiling flag and null money for FOREMAN.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 8.1-8.6, 9.2, 18.1.
 */
class PurchaseBoardIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String BOARD = POSITIONS + "/board";
    private static final List<String> ORDER = List.of("WITH_BUYER", "ORDERED", "SUPPLIER_PAID",
            "IN_DELIVERY", "PARTIALLY_RECEIVED", "RECEIVED");

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode board(UserEntity caller, String role, String query) throws Exception {
        MvcResult result = mockMvc.perform(get(BOARD + query).with(as(caller, role)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode column(JsonNode board, String code) {
        for (JsonNode c : board.get("columns")) {
            if (code.equals(c.get("status").asText())) {
                return c;
            }
        }
        throw new AssertionError("no column " + code);
    }

    @Test
    @DisplayName("six columns in fixed order; positions before WITH_BUYER and CANCELLED never appear")
    void columnsAndGateExclusion() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        for (PositionStatus s : List.of(PositionStatus.PLANNED, PositionStatus.PROPOSED,
                PositionStatus.ACCEPTED, PositionStatus.IN_PROFORMA, PositionStatus.CLIENT_PAID,
                PositionStatus.CANCELLED)) {
            position(project, SourcingPath.AGREED, s);
        }
        position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        position(project, SourcingPath.PACKAGE, PositionStatus.ORDERED);
        position(project, SourcingPath.CLIENT_OWN, PositionStatus.IN_DELIVERY);
        position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        em.flush();
        em.clear();

        JsonNode board = board(buyer, "BUYER", "?projectId=" + project.getId());

        assertThat(board.get("columns")).hasSize(6);
        List<String> codes = new java.util.ArrayList<>();
        long total = 0;
        for (JsonNode c : board.get("columns")) {
            codes.add(c.get("status").asText());
            assertThat(c.get("statusCode").asText()).isNotBlank();
            assertThat(c.get("hasMore").asBoolean()).isFalse();
            assertThat(c.get("cards")).hasSize((int) c.get("count").asLong());
            total += c.get("count").asLong();
        }
        assertThat(codes).containsExactlyElementsOf(ORDER);
        assertThat(total).isEqualTo(4);
        assertThat(column(board, "WITH_BUYER").get("count").asLong()).isEqualTo(1);
        assertThat(column(board, "SUPPLIER_PAID").get("count").asLong()).isZero();
        JsonNode card = column(board, "ORDERED").get("cards").get(0);
        assertThat(card.get("projectId").asLong()).isEqualTo(project.getId());
        assertThat(card.get("projectName").asText()).isEqualTo(project.getName());
        assertThat(card.get("sourcingPath").asText()).isEqualTo("PACKAGE");
        assertThat(card.get("unitName").asText()).isNotBlank();
        assertThat(card.get("material").get("model").asText()).startsWith("Model ");
    }

    @Test
    @DisplayName("cards ordered by site-delivery date ascending, nulls last, then id; limit and hasMore")
    void orderAndLimit() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        PurchasePositionEntity noDate1 = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        PurchasePositionEntity needBy = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        needBy.setNeedByDate(today().plusDays(10));
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        planned.setNeedByDate(today().plusDays(30));
        planned.setPlannedDeliveryDate(today().plusDays(2)); // planned delivery wins
        PurchasePositionEntity noDate2 = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        em.flush();
        em.clear();

        JsonNode all = column(board(buyer, "BUYER", "?projectId=" + project.getId()), "ORDERED");
        assertThat(all.get("count").asLong()).isEqualTo(4);
        assertThat(all.get("hasMore").asBoolean()).isFalse();
        List<Long> ids = new java.util.ArrayList<>();
        all.get("cards").forEach(c -> ids.add(c.get("positionId").asLong()));
        assertThat(ids).containsExactly(planned.getId(), needBy.getId(), noDate1.getId(), noDate2.getId());
        assertThat(all.get("cards").get(0).get("siteDeliveryDate").asText())
                .isEqualTo(today().plusDays(2).toString());

        JsonNode limited = column(board(buyer, "BUYER", "?projectId=" + project.getId() + "&limit=3"),
                "ORDERED");
        assertThat(limited.get("count").asLong()).isEqualTo(4);
        assertThat(limited.get("cards")).hasSize(3);
        assertThat(limited.get("hasMore").asBoolean()).isTrue();
        assertThat(limited.get("cards").get(0).get("positionId").asLong()).isEqualTo(planned.getId());
    }

    @Test
    @DisplayName("limit outside 1..100 is rejected with 400")
    void invalidLimit() throws Exception {
        UserEntity buyer = user("BUYER");
        for (String limit : List.of("0", "101", "-5")) {
            mockMvc.perform(get(BOARD + "?limit=" + limit).with(as(buyer, "BUYER")))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get(BOARD + "?limit=100").with(as(buyer, "BUYER"))).andExpect(status().isOk());
        mockMvc.perform(get(BOARD + "?limit=1").with(as(buyer, "BUYER"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("hideDelivered returns RECEIVED with count and no cards")
    void hideDelivered() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        position(project, SourcingPath.PACKAGE, PositionStatus.RECEIVED);
        position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        em.flush();
        em.clear();

        JsonNode hidden = board(buyer, "BUYER", "?projectId=" + project.getId() + "&hideDelivered=true");
        JsonNode received = column(hidden, "RECEIVED");
        assertThat(received.get("count").asLong()).isEqualTo(2);
        assertThat(received.get("cards")).isEmpty();
        assertThat(received.get("hasMore").asBoolean()).isFalse();
        assertThat(column(hidden, "IN_DELIVERY").get("cards")).hasSize(1);

        JsonNode shown = board(buyer, "BUYER", "?projectId=" + project.getId() + "&hideDelivered=false");
        assertThat(column(shown, "RECEIVED").get("cards")).hasSize(2);
    }

    @Test
    @DisplayName("STAFF_FULL sees money and the over-ceiling flag; FOREMAN gets null money")
    void ceilingFlagAndForemanNullMoney() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        UserEntity foreman = memberUser("FOREMAN", project);
        PurchasePositionEntity over = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        over.setSalePrice(new BigDecimal("100.00"));
        over.setActualPurchasePrice(new BigDecimal("120.00"));
        PurchasePositionEntity within = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        within.setSalePrice(new BigDecimal("100.00"));
        within.setActualPurchasePrice(new BigDecimal("90.00"));
        em.flush();
        em.clear();

        JsonNode staff = column(board(buyer, "BUYER", "?projectId=" + project.getId()), "WITH_BUYER");
        assertThat(staff.get("cards")).hasSize(2);
        for (JsonNode card : staff.get("cards")) {
            JsonNode money = card.get("money");
            assertThat(money.isNull()).isFalse();
            assertThat(money.get("salePrice").decimalValue()).isEqualByComparingTo("100.00");
            assertThat(money.get("plannedPurchasePrice").isNull()).isFalse();
            boolean isOver = card.get("positionId").asLong() == over.getId();
            assertThat(money.get("overCeiling").asBoolean()).isEqualTo(isOver);
            if (isOver) {
                assertThat(money.get("actualPurchasePrice").decimalValue()).isEqualByComparingTo("120.00");
            }
        }

        JsonNode site = column(board(foreman, "FOREMAN", "?projectId=" + project.getId()), "WITH_BUYER");
        assertThat(site.get("count").asLong()).isEqualTo(2);
        assertThat(site.get("cards")).hasSize(2);
        for (JsonNode card : site.get("cards")) {
            assertThat(card.get("money") == null || card.get("money").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("scope and filters: FOREMAN limited to own projects, buyer modes, text search")
    void scopeAndFilters() throws Exception {
        ProjectEntity mine = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        UserEntity foreman = memberUser("FOREMAN", mine);
        UserEntity buyer = user("BUYER");
        PurchasePositionEntity inMine = position(mine, SourcingPath.AGREED, PositionStatus.ORDERED);
        PurchasePositionEntity inOther = position(other, SourcingPath.AGREED, PositionStatus.ORDERED);
        PurchaseRequestEntity assigned = request(other);
        assigned.setAssignedBuyer(buyer);
        attach(inOther, assigned);
        em.flush();
        em.clear();

        JsonNode foremanBoard = board(foreman, "FOREMAN", "");
        assertThat(column(foremanBoard, "ORDERED").get("cards")).hasSize(1);
        assertThat(column(foremanBoard, "ORDERED").get("cards").get(0).get("positionId").asLong())
                .isEqualTo(inMine.getId());

        // BUYER sees all projects; MINE keeps only positions of requests assigned to the caller
        JsonNode mineBoard = board(buyer, "BUYER", "?buyer=MINE&projectId=" + other.getId());
        assertThat(column(mineBoard, "ORDERED").get("cards")).hasSize(1);
        JsonNode unassigned = board(buyer, "BUYER", "?buyer=UNASSIGNED&projectId=" + other.getId());
        assertThat(column(unassigned, "ORDERED").get("count").asLong()).isZero();

        JsonNode byRequest = board(buyer, "BUYER",
                "?projectId=" + other.getId() + "&q=" + assigned.getReferenceNumber().toLowerCase());
        assertThat(column(byRequest, "ORDERED").get("cards")).hasSize(1);
        JsonNode noMatch = board(buyer, "BUYER", "?projectId=" + other.getId() + "&q=zzz-no-such-text");
        assertThat(column(noMatch, "ORDERED").get("count").asLong()).isZero();
        JsonNode card = column(byRequest, "ORDERED").get("cards").get(0);
        assertThat(card.get("requestId").asLong()).isEqualTo(assigned.getId());
        assertThat(card.get("requestReferenceNumber").asText()).isEqualTo(assigned.getReferenceNumber());
    }
}
