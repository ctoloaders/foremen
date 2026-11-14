package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

import jakarta.persistence.EntityManagerFactory;

/**
 * FOR-11-01 task 6.1 - {@code GET /api/purchase-positions/planner} over HTTP: filters compose, default
 * sort, {@code q} escaping, {@code buyer} modes, hideDelivered, project scoping, statement count,
 * {@code allowedActions} and null money for non-staff callers. The service-level cases live in
 * {@link PlannerQueryServiceIntegrationTest}.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 1.1-1.9, 2.1, 2.6, 4.4, 9.2, 9.5, 18.1.
 */
class PurchasePlannerIntegrationTest extends ProcurementIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLANNER = POSITIONS + "/planner";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private JsonNode page(RequestPostProcessor caller, String... params) throws Exception {
        MockHttpServletRequestBuilder req = get(PLANNER);
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        if (caller != null) {
            req = req.with(caller);
        }
        return JSON.readTree(mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("positionId").asLong()));
        return ids;
    }

    @Test
    @DisplayName("default sort: site delivery date ascending, nulls last, id tie-break; sort=status accepted")
    void defaultSortAndSortParam() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity none = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity none2 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity later = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        later.setNeedByDate(today().plusDays(9));
        PurchasePositionEntity sooner = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        sooner.setNeedByDate(today().plusDays(20));
        sooner.setPlannedDeliveryDate(today().plusDays(2));
        em.flush();
        String pid = String.valueOf(project.getId());

        assertThat(ids(page(null, "projectId", pid)))
                .containsExactly(sooner.getId(), later.getId(), none.getId(), none2.getId());
        assertThat(ids(page(null, "projectId", pid, "sort", "needByDate,desc")))
                .containsExactly(sooner.getId(), later.getId(), none.getId(), none2.getId());
        assertThat(ids(page(null, "projectId", pid, "sort", "status,asc")).get(3)).isEqualTo(later.getId());
    }

    @Test
    @DisplayName("invalid slice and unknown sort field answer 400")
    void invalidParameters() throws Exception {
        mockMvc.perform(get(PLANNER).param("slice", "NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PLANNER).param("sort", "salePrice,asc")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("filters compose: status + sourcingPath + slice + urgent + q intersect")
    void filtersCompose() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        FinishingMaterialEntity tile = material(project);
        tile.setModel("Terrazzo_50% Grey");
        PurchasePositionEntity a = position(project, tile, SourcingPath.AGREED, PositionStatus.PLANNED);
        a.setNeedByDate(today().minusDays(1));
        PurchasePositionEntity b = position(project, tile, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        b.setNeedByDate(today().minusDays(1));
        PurchasePositionEntity c = position(project, tile, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        c.setNeedByDate(today().minusDays(1));
        PurchasePositionEntity d = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        d.setNeedByDate(today().minusDays(1));
        PurchasePositionEntity e = position(project, tile, SourcingPath.AGREED, PositionStatus.PLANNED);
        e.setNeedByDate(today().plusDays(90));
        em.flush();
        String pid = String.valueOf(project.getId());

        JsonNode result = page(null, "projectId", pid, "status", "PLANNED", "sourcingPath", "AGREED",
                "slice", "LATE", "urgent", "true", "q", "terrazzo_50%");
        assertThat(ids(result)).containsExactly(a.getId());
        assertThat(result.get("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(page(null, "projectId", pid, "status", "PLANNED", "status", "ACCEPTED", "q", "50%")))
                .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId(), e.getId());
        // LIKE wildcards are literals: '_' and '%' alone must not match everything
        assertThat(ids(page(null, "projectId", pid, "q", "terrazzo%grey"))).isEmpty();
        assertThat(ids(page(null, "projectId", pid, "q", "terrazzo_50"))).hasSize(4);
        assertThat(ids(page(null, "projectId", pid, "q", "zzz_missing"))).isEmpty();
        assertThat(ids(page(null, "projectId", pid, "q", "\\"))).isEmpty();
    }

    @Test
    @DisplayName("buyer modes MINE / UNASSIGNED / ALL, requestId and noRequest")
    void buyerModes() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = memberUser("BUYER", project);
        UserEntity otherBuyer = memberUser("BUYER", project);
        PurchaseRequestEntity mine = request(project);
        mine.setAssignedBuyer(buyer);
        PurchaseRequestEntity theirs = request(project);
        theirs.setAssignedBuyer(otherBuyer);
        PurchaseRequestEntity unassigned = request(project);
        PurchasePositionEntity inMine = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity inTheirs = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity inUnassigned = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity free = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        attach(inMine, mine);
        attach(inTheirs, theirs);
        attach(inUnassigned, unassigned);
        em.flush();
        String pid = String.valueOf(project.getId());
        RequestPostProcessor asBuyer = as(buyer, "BUYER");

        assertThat(ids(page(asBuyer, "projectId", pid, "buyer", "MINE"))).containsExactly(inMine.getId());
        assertThat(ids(page(asBuyer, "projectId", pid, "buyer", "UNASSIGNED")))
                .containsExactlyInAnyOrder(inUnassigned.getId(), free.getId());
        assertThat(ids(page(asBuyer, "projectId", pid, "buyer", "ALL")))
                .containsExactlyInAnyOrder(inMine.getId(), inTheirs.getId(), inUnassigned.getId(), free.getId());
        assertThat(ids(page(asBuyer, "projectId", pid, "requestId", String.valueOf(theirs.getId()))))
                .containsExactly(inTheirs.getId());
        assertThat(ids(page(asBuyer, "projectId", pid, "noRequest", "true"))).containsExactly(free.getId());
        JsonNode row = page(asBuyer, "projectId", pid, "buyer", "MINE").get("content").get(0);
        assertThat(row.get("requestId").asLong()).isEqualTo(mine.getId());
        assertThat(row.get("requestReferenceNumber").asText()).isEqualTo(mine.getReferenceNumber());
    }

    @Test
    @DisplayName("hideDelivered removes RECEIVED rows only")
    void hideDelivered() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity received = position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        PurchasePositionEntity partial = position(project, SourcingPath.AGREED, PositionStatus.PARTIALLY_RECEIVED);
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        String pid = String.valueOf(project.getId());

        assertThat(ids(page(null, "projectId", pid))).contains(received.getId(), partial.getId(), planned.getId());
        assertThat(ids(page(null, "projectId", pid, "hideDelivered", "true")))
                .containsExactlyInAnyOrder(partial.getId(), planned.getId());
        assertThat(ids(page(null, "projectId", pid, "hideDelivered", "false", "status", "RECEIVED")))
                .containsExactly(received.getId());
    }

    @Test
    @DisplayName("scoping: a member sees only own projects; a foreign projectId yields an empty page")
    void scoping() throws Exception {
        ProjectEntity mineProject = project(ProjectStatus.ACTIVE);
        ProjectEntity foreignProject = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", mineProject);
        PurchasePositionEntity mine = position(mineProject, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity foreign = position(foreignProject, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        RequestPostProcessor asManager = as(manager, "MANAGER");

        List<Long> all = ids(page(asManager, "size", "100"));
        assertThat(all).contains(mine.getId()).doesNotContain(foreign.getId());
        assertThat(ids(page(asManager, "projectId", String.valueOf(foreignProject.getId())))).isEmpty();
        assertThat(ids(page(null, "projectId", String.valueOf(foreignProject.getId()))))
                .containsExactly(foreign.getId());
    }

    @Test
    @DisplayName("money block and allowedActions per caller; FOREMAN gets neither")
    void moneyAndActions() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        UserEntity foreman = memberUser("FOREMAN", project);
        position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PurchasePositionEntity cancelled = position(project, SourcingPath.PACKAGE, PositionStatus.CANCELLED);
        em.flush();
        String pid = String.valueOf(project.getId());

        JsonNode asManager = page(as(manager, "MANAGER"), "projectId", pid);
        for (JsonNode row : asManager.get("content")) {
            assertThat(row.get("money").isNull()).isFalse();
            List<String> actions = new ArrayList<>();
            row.get("allowedActions").forEach(n -> actions.add(n.asText()));
            if (row.get("positionId").asLong() == cancelled.getId()) {
                assertThat(actions).contains("REOPEN").doesNotContain("SEND_TO_BUYER", "CANCEL");
            } else {
                assertThat(row.get("money").get("saleNetTotal").decimalValue()).isEqualByComparingTo("500.00");
                assertThat(actions).contains("EDIT", "SEND_TO_BUYER", "CANCEL").doesNotContain("REOPEN");
            }
        }
        JsonNode asForeman = page(as(foreman, "FOREMAN"), "projectId", pid);
        assertThat(asForeman.get("content")).hasSize(2);
        for (JsonNode row : asForeman.get("content")) {
            assertThat(row.get("money") == null || row.get("money").isNull()).isTrue();
            assertThat(row.get("allowedActions")).isEmpty();
        }
        assertThat(asForeman.toString()).doesNotContain("saleNetTotal", "plannedPurchasePrice", "costNetTotal");
    }

    @Test
    @DisplayName("statement count is independent of the number of rows (100 rows vs 10)")
    void statementCountIsConstant() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        FinishingMaterialEntity planned = material(project);
        UserEntity manager = memberUser("MANAGER", project);
        PurchaseRequestEntity request = request(project);
        for (int i = 0; i < 100; i++) {
            PurchasePositionEntity p = position(project, planned, SourcingPath.AGREED,
                    i % 2 == 0 ? PositionStatus.PLANNED : PositionStatus.ACCEPTED);
            p.setNeedByDate(today().plusDays(i % 30 - 5));
            if (i % 3 == 0) {
                p.setPurchaseRequest(request);
            }
        }
        em.flush();
        String pid = String.valueOf(project.getId());
        RequestPostProcessor asManager = as(manager, "MANAGER");
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        page(asManager, "projectId", pid, "size", "100"); // warm-up
        long small = measure(stats, asManager, pid, "10");
        long large = measure(stats, asManager, pid, "100");

        assertThat(page(asManager, "projectId", pid, "size", "100").get("content")).hasSize(100);
        assertThat(large).as("statements for 100 rows equal statements for 10 rows").isEqualTo(small);
        assertThat(large).as("bounded statement count").isLessThan(40);
    }

    private long measure(Statistics stats, RequestPostProcessor caller, String pid, String size) throws Exception {
        em.flush();
        em.clear();
        stats.clear();
        page(caller, "projectId", pid, "size", size);
        return stats.getPrepareStatementCount();
    }
}
