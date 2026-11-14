package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-01 task 6.1 - {@code GET /api/purchase-positions/planner/slice-counts}: counts agree with the
 * totals of the planner endpoint per slice, ignore the {@code slice} parameter and follow the other filters.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 2.2, 2.6.
 */
class PlannerSliceCountsIntegrationTest extends ProcurementIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COUNTS = POSITIONS + "/planner/slice-counts";
    private static final String PLANNER = POSITIONS + "/planner";

    private JsonNode body(String url, String... params) throws Exception {
        var req = get(url);
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        String content = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return JSON.readTree(content);
    }

    private ProjectEntity seed() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity late = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        late.setNeedByDate(today().minusDays(2));
        PurchasePositionEntity onTime = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        onTime.setNeedByDate(today().plusDays(60));
        position(project, SourcingPath.AGREED, PositionStatus.CLIENT_PAID);
        position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        position(project, SourcingPath.PACKAGE, PositionStatus.RECEIVED);
        position(project, SourcingPath.AGREED, PositionStatus.CANCELLED);
        em.flush();
        return project;
    }

    @Test
    @DisplayName("each slice count equals the planner total of that slice; ALL is the unsliced total")
    void countsEqualPlannerTotals() throws Exception {
        ProjectEntity project = seed();
        String pid = String.valueOf(project.getId());

        JsonNode counts = body(COUNTS, "projectId", pid);
        assertThat(counts.size()).isEqualTo(6);
        for (String slice : List.of("ALL", "ON_SCHEDULE", "LATE", "CHANGED", "PAID", "NOT_PAID")) {
            long total = body(PLANNER, "projectId", pid, "slice", slice).get("totalElements").asLong();
            assertThat(counts.get(slice).asLong()).as(slice).isEqualTo(total);
        }
        assertThat(counts.get("ALL").asLong()).isEqualTo(7);
        assertThat(counts.get("LATE").asLong()).isEqualTo(1);
        assertThat(counts.get("PAID").asLong()).isEqualTo(2);     // CLIENT_PAID, ORDERED (AGREED only)
        assertThat(counts.get("NOT_PAID").asLong()).isEqualTo(2); // PLANNED, ACCEPTED (AGREED only)
        assertThat(counts.get("CHANGED").asLong()).isZero();
    }

    @Test
    @DisplayName("slice parameter is ignored; other filters narrow the counts; hideDelivered applies")
    void sliceIgnoredFiltersApply() throws Exception {
        ProjectEntity project = seed();
        String pid = String.valueOf(project.getId());

        Map<String, Long> base = toMap(body(COUNTS, "projectId", pid));
        Map<String, Long> withSlice = toMap(body(COUNTS, "projectId", pid, "slice", "LATE"));
        assertThat(withSlice).isEqualTo(base);

        JsonNode packageOnly = body(COUNTS, "projectId", pid, "sourcingPath", "PACKAGE");
        assertThat(packageOnly.get("ALL").asLong()).isEqualTo(2);
        assertThat(packageOnly.get("PAID").asLong()).isZero();
        assertThat(packageOnly.get("NOT_PAID").asLong()).isZero();

        JsonNode hidden = body(COUNTS, "projectId", pid, "hideDelivered", "true");
        assertThat(hidden.get("ALL").asLong()).isEqualTo(base.get("ALL") - 1);
    }

    @Test
    @DisplayName("counts are scoped to the caller's projects")
    void countsAreScoped() throws Exception {
        ProjectEntity mine = project(ProjectStatus.ACTIVE);
        ProjectEntity foreign = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", mine);
        position(mine, SourcingPath.AGREED, PositionStatus.PLANNED);
        position(foreign, SourcingPath.AGREED, PositionStatus.PLANNED);
        position(foreign, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();

        String content = mockMvc.perform(get(COUNTS).with(as(manager, "MANAGER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(content).get("ALL").asLong()).isEqualTo(1);
        content = mockMvc.perform(get(COUNTS).param("projectId", String.valueOf(foreign.getId()))
                        .with(as(manager, "MANAGER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(content).get("ALL").asLong()).isZero();
    }

    private static Map<String, Long> toMap(JsonNode node) {
        Map<String, Long> map = new java.util.TreeMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asLong()));
        return map;
    }
}
