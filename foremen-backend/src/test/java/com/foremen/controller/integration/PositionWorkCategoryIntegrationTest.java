package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;

/**
 * FOR-11-01 task 6.1 - the work-category link of a position: manual override, automatic tiers
 * (estimate, then catalog), candidates, risk flag, scheduled-categories search and work-link preview
 * (including the 404 cases), all through HTTP.
 *
 * <p>Start dates: the project starts on {@link #START}; a bar at half-day {@code h} starts on
 * {@code START + (h + 1) / 2 - 1} days.
 *
 * <p>Repeatability: unique codes per run inside a rolled-back transaction.
 *
 * <p>Validates Requirements 5.1-5.10, 18.1.
 */
class PositionWorkCategoryIntegrationTest extends ProcurementIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2031, 3, 3);
    private static final String PLANNER = POSITIONS + "/planner";
    private static final String SCHEDULED = POSITIONS + "/scheduled-categories";
    private static final String PREVIEW = POSITIONS + "/work-link-preview";

    // ------------------------------------------------------------------ fixtures

    private ProjectEntity scheduledProject() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        project.setStartDate(START);
        em.flush();
        return project;
    }

    private WorkCategoryEntity category(String key, int order) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode("pwc_" + key + "_" + unique());
        c.setOrderNo(order);
        c.setNameRU("Кат " + key);
        c.setNamePL("Kat " + key);
        c.setActive(true);
        em.persist(c);
        return c;
    }

    private WorkItemEntity work(WorkCategoryEntity category) {
        WorkItemEntity w = new WorkItemEntity();
        w.setWorkCategory(category);
        w.setUnit(unit);
        w.setCode("pwc_w_" + unique());
        w.setNameRU("Работа");
        w.setNamePL("Praca");
        w.setActive(true);
        em.persist(w);
        return w;
    }

    private void consumes(WorkItemEntity work) {
        WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
        c.setWorkItem(work);
        c.setMaterialUnit(unit);
        c.setBranch(ConsumptionBranch.finishing);
        c.setFinishingMaterialType(type);
        c.setNormQty(new BigDecimal("1.0000"));
        c.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
        c.setSourceType("MANUAL");
        c.setSourceDoc("test");
        c.setSourceRef("test");
        em.persist(c);
    }

    /** Estimate of the project with one line per given work (this defines the schedule rows). */
    private void estimate(ProjectEntity project, WorkItemEntity... works) {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("pwc_" + unique());
        currency.setSymbol("zl");
        currency.setNameRU("Злотый");
        currency.setNamePL("Złoty");
        em.persist(currency);
        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(project);
        estimate.setCurrency(currency);
        em.persist(estimate);
        int no = 1;
        for (WorkItemEntity w : works) {
            EstimateLineEntity line = new EstimateLineEntity();
            line.setEstimate(estimate);
            line.setWorkItem(w);
            line.setUnit(unit);
            line.setLineNo(no++);
            line.setUnitPrice(new BigDecimal("10.00"));
            line.setQuantity(BigDecimal.ONE);
            line.setValueNet(new BigDecimal("10.00"));
            em.persist(line);
        }
        em.flush();
    }

    /** Stored schedule: one bar per category; {@code startHalfDays} parallel to {@code categories}. */
    private void schedule(ProjectEntity project, List<WorkCategoryEntity> categories, int... startHalfDays) {
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setProject(project);
        for (int i = 0; i < categories.size(); i++) {
            ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
            bar.setSchedule(schedule);
            bar.setWorkCategory(categories.get(i));
            bar.setStartHalfDays(startHalfDays[i]);
            bar.setDurationHalfDays(4);
            schedule.getBars().add(bar);
        }
        em.persist(schedule);
        em.flush();
    }

    private static LocalDate startOf(int halfDay) {
        return START.plusDays((halfDay + 1) / 2 - 1L);
    }

    private JsonNode get(String url, RequestPostProcessor caller, int expectedStatus, String... params)
            throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        if (caller != null) {
            req = req.with(caller);
        }
        String body = mockMvc.perform(req).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString();
        return body.isBlank() ? null : JSON.readTree(body);
    }

    private JsonNode row(ProjectEntity project, PurchasePositionEntity position) throws Exception {
        for (JsonNode n : get(PLANNER, null, 200, "projectId", String.valueOf(project.getId())).get("content")) {
            if (n.get("positionId").asLong() == position.getId()) {
                return n;
            }
        }
        throw new AssertionError("row not found " + position.getId());
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }

    // ------------------------------------------------------------------ automatic tiers

    @Test
    @DisplayName("tier 1: a scheduled category of an estimate work that consumes the type is linked automatically")
    void autoTier1() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity a = category("A", 1);
        WorkCategoryEntity b = category("B", 2);
        WorkItemEntity wa = work(a);
        WorkItemEntity wb = work(b);
        consumes(wa);
        estimate(project, wa, wb);
        schedule(project, List.of(a, b), 5, 1);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        JsonNode row = row(project, p);
        assertThat(row.get("workCategorySource").asText()).isEqualTo("AUTO");
        assertThat(row.get("effectiveWorkCategoryId").asLong()).isEqualTo(a.getId());
        assertThat(row.get("workCategoryId").isNull()).isTrue();
        assertThat(row.get("workCategoryCandidates")).isEmpty();
        assertThat(date(row.get("workStartDate"))).isEqualTo(startOf(5));
    }

    @Test
    @DisplayName("tier 2: the catalog is used only when the estimate works give no scheduled match")
    void autoTier2() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity b = category("B", 1);
        WorkItemEntity inEstimate = work(b);   // in the estimate, consumes nothing
        WorkItemEntity inCatalog = work(b);    // catalog only, consumes the type
        consumes(inCatalog);
        estimate(project, inEstimate);
        schedule(project, List.of(b), 3);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        JsonNode row = row(project, p);
        assertThat(row.get("workCategorySource").asText()).isEqualTo("AUTO");
        assertThat(row.get("effectiveWorkCategoryId").asLong()).isEqualTo(b.getId());
        assertThat(date(row.get("workStartDate"))).isEqualTo(startOf(3));
    }

    @Test
    @DisplayName("NONE when the category is consumed but not scheduled, or nothing is scheduled")
    void autoNone() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity a = category("A", 1);
        WorkCategoryEntity other = category("O", 2);
        WorkItemEntity wa = work(a);
        WorkItemEntity wo = work(other);
        consumes(wa);
        estimate(project, wa, wo);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        assertThat(row(project, p).get("workCategorySource").asText()).isEqualTo("NONE");

        schedule(project, List.of(other), 1); // scheduled, but the type is not consumed there
        JsonNode row = row(project, p);
        assertThat(row.get("workCategorySource").asText()).isEqualTo("NONE");
        assertThat(row.get("effectiveWorkCategoryId").isNull()).isTrue();
        assertThat(row.get("deliveryRisk").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("several matches: earliest start wins, the others are candidates")
    void candidates() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity late = category("L", 1);
        WorkCategoryEntity early = category("E", 2);
        WorkCategoryEntity mid = category("M", 3);
        WorkItemEntity wl = work(late);
        WorkItemEntity we = work(early);
        WorkItemEntity wm = work(mid);
        consumes(wl);
        consumes(we);
        consumes(wm);
        estimate(project, wl, we, wm);
        schedule(project, List.of(late, early, mid), 21, 1, 9);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        JsonNode row = row(project, p);
        assertThat(row.get("effectiveWorkCategoryId").asLong()).isEqualTo(early.getId());
        List<Long> candidates = new ArrayList<>();
        row.get("workCategoryCandidates").forEach(c -> candidates.add(c.get("id").asLong()));
        assertThat(candidates).containsExactly(mid.getId(), late.getId());
    }

    // ------------------------------------------------------------------ manual override

    @Test
    @DisplayName("manual override wins over the automatic link, also for a category that is not scheduled")
    void manualOverride() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity auto = category("A", 1);
        WorkCategoryEntity manual = category("MAN", 2);
        WorkCategoryEntity unscheduled = category("U", 3);
        WorkItemEntity wa = work(auto);
        WorkItemEntity wm = work(manual);
        consumes(wa);
        estimate(project, wa, wm);
        schedule(project, List.of(auto, manual), 1, 7);
        FinishingMaterialEntity planned = material(project);

        Long scheduledId = createWithCategory(project, planned, manual.getId());
        Long unscheduledId = createWithCategory(project, planned, unscheduled.getId());
        Long autoId = createWithCategory(project, planned, null);
        em.flush();
        em.clear();

        JsonNode rows = get(PLANNER, null, 200, "projectId", String.valueOf(project.getId()), "size", "100")
                .get("content");
        for (JsonNode n : rows) {
            long id = n.get("positionId").asLong();
            if (id == scheduledId) {
                assertThat(n.get("workCategorySource").asText()).isEqualTo("MANUAL");
                assertThat(n.get("workCategoryId").asLong()).isEqualTo(manual.getId());
                assertThat(n.get("effectiveWorkCategoryId").asLong()).isEqualTo(manual.getId());
                assertThat(date(n.get("workStartDate"))).isEqualTo(startOf(7));
            } else if (id == unscheduledId) {
                assertThat(n.get("workCategorySource").asText()).isEqualTo("MANUAL");
                assertThat(n.get("effectiveWorkCategoryId").asLong()).isEqualTo(unscheduled.getId());
                assertThat(date(n.get("workStartDate"))).isNull();
                assertThat(n.get("deliveryRisk").asBoolean()).isFalse();
            } else if (id == autoId) {
                assertThat(n.get("workCategorySource").asText()).isEqualTo("AUTO");
                assertThat(n.get("effectiveWorkCategoryId").asLong()).isEqualTo(auto.getId());
            }
        }
        assertThat(rows).hasSize(3);
    }

    @Test
    @DisplayName("an unknown work category on create is rejected with 400")
    void manualInvalidCategory() throws Exception {
        ProjectEntity project = scheduledProject();
        FinishingMaterialEntity planned = material(project);
        mockMvc.perform(post(POSITIONS).contentType(MediaType.APPLICATION_JSON)
                        .content(createPositionJson(project.getId(), planned.getId(), "\"workCategoryId\": 999999999")))
                .andExpect(status().isBadRequest());
    }

    private Long createWithCategory(ProjectEntity project, FinishingMaterialEntity planned, Long categoryId)
            throws Exception {
        String extra = categoryId == null ? "" : "\"workCategoryId\": " + categoryId;
        String body = mockMvc.perform(post(POSITIONS).contentType(MediaType.APPLICATION_JSON)
                        .content(createPositionJson(project.getId(), planned.getId(), extra)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    // ------------------------------------------------------------------ risk flag

    @Test
    @DisplayName("deliveryRisk: site delivery strictly after the work start")
    void riskFlag() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity a = category("A", 1);
        WorkItemEntity wa = work(a);
        consumes(wa);
        estimate(project, wa);
        schedule(project, List.of(a), 11); // starts START + 5
        LocalDate workStart = startOf(11);
        PurchasePositionEntity after = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        after.setNeedByDate(workStart.plusDays(1));
        PurchasePositionEntity onDay = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        onDay.setNeedByDate(workStart);
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        planned.setNeedByDate(workStart.plusDays(10));
        planned.setPlannedDeliveryDate(workStart.minusDays(1)); // planned delivery wins over need-by
        PurchasePositionEntity noDate = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();

        assertThat(row(project, after).get("deliveryRisk").asBoolean()).isTrue();
        assertThat(row(project, onDay).get("deliveryRisk").asBoolean()).isFalse();
        assertThat(row(project, planned).get("deliveryRisk").asBoolean()).isFalse();
        assertThat(row(project, noDate).get("deliveryRisk").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ scheduled-categories

    @Test
    @DisplayName("scheduled-categories: earliest start first, q over RU and PL names, orphan bars ignored")
    void scheduledCategories() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity floor = category("Floor", 1);
        WorkCategoryEntity wall = category("Wall", 2);
        WorkCategoryEntity noBar = category("NoBar", 3);
        WorkCategoryEntity orphan = category("Orphan", 4);
        floor.setNameRU("Полы");
        floor.setNamePL("Podłogi");
        WorkItemEntity wf = work(floor);
        WorkItemEntity ww = work(wall);
        WorkItemEntity wn = work(noBar);
        estimate(project, wf, ww, wn);
        schedule(project, List.of(floor, wall, orphan), 9, 1, 1);
        String pid = String.valueOf(project.getId());

        JsonNode all = get(SCHEDULED, null, 200, "projectId", pid);
        List<Long> ids = new ArrayList<>();
        all.forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).containsExactly(wall.getId(), floor.getId());
        assertThat(date(all.get(0).get("startDate"))).isEqualTo(startOf(1));
        assertThat(date(all.get(1).get("startDate"))).isEqualTo(startOf(9));

        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "ПОЛ")).hasSize(1);
        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "podł")).hasSize(1);
        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "kat wall")).hasSize(1);
        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "  ")).hasSize(2);
        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "nothing-like-this")).isEmpty();
        assertThat(get(SCHEDULED, null, 200, "projectId", pid, "q", "Orphan"))
                .isEmpty();
    }

    @Test
    @DisplayName("scheduled-categories: 404 for an inaccessible or unknown project, 200 for a member")
    void scheduledCategoriesAccess() throws Exception {
        ProjectEntity mine = scheduledProject();
        ProjectEntity foreign = scheduledProject();
        UserEntity manager = memberUser("MANAGER", mine);
        RequestPostProcessor asManager = as(manager, "MANAGER");

        get(SCHEDULED, asManager, 200, "projectId", String.valueOf(mine.getId()));
        get(SCHEDULED, asManager, 404, "projectId", String.valueOf(foreign.getId()));
        get(SCHEDULED, asManager, 404, "projectId", "999999999");
    }

    // ------------------------------------------------------------------ work-link-preview

    @Test
    @DisplayName("work-link-preview: matches the automatic link of a position of the same material")
    void previewMatchesPlanner() throws Exception {
        ProjectEntity project = scheduledProject();
        WorkCategoryEntity late = category("L", 1);
        WorkCategoryEntity early = category("E", 2);
        WorkItemEntity wl = work(late);
        WorkItemEntity we = work(early);
        consumes(wl);
        consumes(we);
        estimate(project, wl, we);
        schedule(project, List.of(late, early), 13, 3);
        FinishingMaterialEntity planned = material(project);

        JsonNode preview = get(PREVIEW, null, 200, "projectId", String.valueOf(project.getId()),
                "materialId", String.valueOf(planned.getId()));
        assertThat(preview.get("workCategorySource").asText()).isEqualTo("AUTO");
        assertThat(preview.get("effectiveWorkCategoryId").asLong()).isEqualTo(early.getId());
        assertThat(preview.get("effectiveWorkCategoryName").asText()).isNotBlank();
        assertThat(date(preview.get("startDate"))).isEqualTo(startOf(3));
        assertThat(preview.get("workCategoryCandidates")).hasSize(1);
        assertThat(preview.get("workCategoryCandidates").get(0).get("id").asLong()).isEqualTo(late.getId());

        PurchasePositionEntity p = position(project, planned, SourcingPath.AGREED, PositionStatus.PLANNED);
        JsonNode row = row(project, p);
        assertThat(row.get("effectiveWorkCategoryId").asLong())
                .isEqualTo(preview.get("effectiveWorkCategoryId").asLong());
    }

    @Test
    @DisplayName("work-link-preview: NONE without a schedule; 404 for unknown material and inaccessible project")
    void previewNoneAndNotFound() throws Exception {
        ProjectEntity mine = scheduledProject();
        ProjectEntity foreign = scheduledProject();
        UserEntity manager = memberUser("MANAGER", mine);
        RequestPostProcessor asManager = as(manager, "MANAGER");
        FinishingMaterialEntity planned = material(mine);
        String pid = String.valueOf(mine.getId());
        String mid = String.valueOf(planned.getId());

        JsonNode none = get(PREVIEW, asManager, 200, "projectId", pid, "materialId", mid);
        assertThat(none.get("workCategorySource").asText()).isEqualTo("NONE");
        assertThat(none.get("effectiveWorkCategoryId").isNull()).isTrue();
        assertThat(none.get("workCategoryCandidates")).isEmpty();

        get(PREVIEW, asManager, 404, "projectId", pid, "materialId", "999999999");
        get(PREVIEW, asManager, 404, "projectId", String.valueOf(foreign.getId()), "materialId", mid);
        get(PREVIEW, asManager, 404, "projectId", "999999999", "materialId", mid);
    }
}
