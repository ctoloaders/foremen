package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-01 task 6.2 - {@code GET /api/purchase-positions/planner/groups}: group order (earliest site
 * delivery, nulls last, then request id), the synthetic "no request" group, cancelled positions excluded
 * from totals, reconciliation of group totals with the flat list, and reserved {@code groupBy} values.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 3.1, 3.2, 3.4, 3.5.
 */
class PlannerGroupsIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String GROUPS = POSITIONS + "/planner/groups";
    private static final String PLANNER = POSITIONS + "/planner";

    @Autowired
    private ObjectMapper json;

    private JsonNode body(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private PurchasePositionEntity pos(ProjectEntity project, PurchaseRequestEntity request,
                                       PositionStatus status, Integer plusDays) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        if (plusDays != null) {
            p.setNeedByDate(today().plusDays(plusDays));
        }
        if (request != null) {
            attach(p, request);
        }
        em.flush();
        return p;
    }

    @Test
    @DisplayName("groups are ordered by earliest site delivery (nulls last), then request id; synthetic group carries no request")
    void orderAndSyntheticGroup() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r1 = request(project);
        PurchaseRequestEntity r2 = request(project);
        PurchaseRequestEntity r3 = request(project);
        pos(project, r1, PositionStatus.PLANNED, 10);
        pos(project, r1, PositionStatus.PLANNED, 20);
        pos(project, r2, PositionStatus.PLANNED, 5);
        pos(project, r3, PositionStatus.PLANNED, null); // nulls last
        pos(project, null, PositionStatus.PLANNED, 8);  // synthetic group, between R2 and R1
        em.flush();
        em.clear();

        JsonNode content = body(GROUPS + "?projectId=" + project.getId()).get("content");

        assertThat(content).hasSize(4);
        assertThat(content.get(0).get("requestId").asLong()).isEqualTo(r2.getId());
        assertThat(content.get(1).get("requestId").isNull()).isTrue();
        assertThat(content.get(1).get("key").asText()).isEqualTo("NO_REQUEST");
        assertThat(content.get(1).get("referenceNumber").isNull()).isTrue();
        assertThat(content.get(1).get("positionCount").asLong()).isEqualTo(1);
        assertThat(content.get(2).get("requestId").asLong()).isEqualTo(r1.getId());
        assertThat(content.get(2).get("positionCount").asLong()).isEqualTo(2);
        assertThat(content.get(2).get("referenceNumber").asText()).isEqualTo(r1.getReferenceNumber());
        assertThat(content.get(3).get("requestId").asLong()).isEqualTo(r3.getId());
    }

    @Test
    @DisplayName("cancelled positions are counted but excluded from group totals")
    void cancelledExcludedFromTotals() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        pos(project, r, PositionStatus.PLANNED, 3);
        pos(project, r, PositionStatus.CANCELLED, 3);
        em.flush();
        em.clear();

        JsonNode group = body(GROUPS + "?projectId=" + project.getId()).get("content").get(0);

        assertThat(group.get("positionCount").asLong()).isEqualTo(2);
        // one live position: 5 x 100.00 net sale
        assertThat(group.get("totals").get("saleNet").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("groupBy=NONE returns a single synthetic group over all positions")
    void groupByNone() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        pos(project, r, PositionStatus.PLANNED, 3);
        pos(project, null, PositionStatus.PLANNED, 4);
        em.flush();
        em.clear();

        JsonNode content = body(GROUPS + "?groupBy=NONE&projectId=" + project.getId()).get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("key").asText()).isEqualTo("ALL");
        assertThat(content.get(0).get("requestId").isNull()).isTrue();
        assertThat(content.get(0).get("positionCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("reserved and unknown groupBy values give 400 (localized error.purchasePlanner.groupBy.invalid)")
    void reservedGroupBy() throws Exception {
        for (String value : List.of("EXTERNAL_ORDER", "PROFORMA", "bogus")) {
            mockMvc.perform(get(GROUPS + "?groupBy=" + value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Nieprawidłowy sposób grupowania."));
        }
    }

    @Test
    @DisplayName("sum of group totals over all pages equals the totals of the flat list (R3.5)")
    void groupTotalsReconcileWithFlatList() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r1 = request(project);
        PurchaseRequestEntity r2 = request(project);
        PurchaseRequestEntity r3 = request(project);
        PurchasePositionEntity a = pos(project, r1, PositionStatus.PLANNED, 1);
        a.setQuantity(new BigDecimal("3.333"));
        PurchasePositionEntity b = pos(project, r2, PositionStatus.ORDERED, 2);
        b.setQuantity(new BigDecimal("7.777"));
        PurchasePositionEntity c = pos(project, r3, PositionStatus.PLANNED, 3);
        c.setQuantity(new BigDecimal("1.111"));
        PurchasePositionEntity d = pos(project, null, PositionStatus.PLANNED, 4);
        d.setQuantity(new BigDecimal("2.222"));
        pos(project, r3, PositionStatus.CANCELLED, 3);
        em.flush();
        em.clear();

        String query = "projectId=" + project.getId();
        BigDecimal groupSaleNet = BigDecimal.ZERO;
        BigDecimal groupSaleGross = BigDecimal.ZERO;
        BigDecimal groupCostNet = BigDecimal.ZERO;
        BigDecimal groupDelivery = BigDecimal.ZERO;
        long groupCount = 0;
        for (int page = 0; page < 4; page++) { // size 2 -> 4 groups over 2 pages, 3rd/4th empty
            JsonNode groups = body(GROUPS + "?" + query + "&size=2&page=" + page);
            for (JsonNode g : groups.get("content")) {
                JsonNode t = g.get("totals");
                groupSaleNet = groupSaleNet.add(t.get("saleNet").decimalValue());
                groupSaleGross = groupSaleGross.add(t.get("saleGross").decimalValue());
                groupCostNet = groupCostNet.add(t.get("costNet").decimalValue());
                groupDelivery = groupDelivery.add(t.get("deliveryCost").decimalValue());
                groupCount += g.get("positionCount").asLong();
            }
        }

        BigDecimal flatSaleNet = BigDecimal.ZERO;
        BigDecimal flatSaleGross = BigDecimal.ZERO;
        BigDecimal flatCostNet = BigDecimal.ZERO;
        BigDecimal flatDelivery = BigDecimal.ZERO;
        JsonNode flat = body(PLANNER + "?" + query + "&size=100");
        List<JsonNode> rows = new ArrayList<>();
        flat.get("content").forEach(rows::add);
        for (JsonNode row : rows) {
            if ("CANCELLED".equals(row.get("status").asText())) {
                continue;
            }
            JsonNode m = row.get("money");
            flatSaleNet = flatSaleNet.add(m.get("saleNetTotal").decimalValue());
            flatSaleGross = flatSaleGross.add(m.get("saleGrossTotal").decimalValue());
            flatCostNet = flatCostNet.add(m.get("costNetTotal").decimalValue());
            flatDelivery = flatDelivery.add(m.get("deliveryCostShare").decimalValue());
        }

        assertThat(groupCount).isEqualTo(rows.size());
        assertThat(groupSaleNet).isEqualByComparingTo(flatSaleNet);
        assertThat(groupSaleGross).isEqualByComparingTo(flatSaleGross);
        assertThat(groupCostNet).isEqualByComparingTo(flatCostNet);
        assertThat(groupDelivery).isEqualByComparingTo(flatDelivery);
    }
}
