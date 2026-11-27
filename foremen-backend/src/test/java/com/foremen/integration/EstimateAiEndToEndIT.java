package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.estimateai.ai.EstimatePlanClient;
import com.foremen.service.estimateai.ai.StubEstimatePlanClient;
import com.foremen.service.offer.OfferSnapshot;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-04 task 6.1 - the {@code ESTIMATE} job end to end with the stub vision and the stub planner against
 * the real schema: stage order, rooms saved before completion, the project lock while the job runs, the job's
 * own pipeline writing no AI estimate lines, a restorable AI offer whose snapshot carries the material lines,
 * the completion notification exactly once and the 404 of the offer for a client.
 *
 *
 * <p>Repeatability: generator strategy. Project, users and prompt carry the per-instance run id; the shared
 * catalog fixture (one work item with one material norm) is created once under fixed codes and reused.
 *
 * <p>Validates: Requirements 2.2, 3.3, 4.1, 8.1, 8.5, 8.6, 16.1, 23.1
 */
@TestPropertySource(properties = {
        "foremen.estimate-ai.enabled=true",
        "foremen.estimate-ai.llm.stub=true"
})
@Import(EstimateAiEndToEndIT.GateConfig.class)
class EstimateAiEndToEndIT extends RoomsAutoimportItBase {

    private static final String WORK_CODE = "000-AIIT-WORK";
    private static final String MATERIAL_TYPE_CODE = "000-AIIT-MAT";
    private static final String BLOCK_MARKER = "TEST:BLOCK";

    static volatile CountDownLatch gate = new CountDownLatch(1);
    static volatile CountDownLatch entered = new CountDownLatch(1);

    @Autowired private CurrencyDao currencyDao;

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfig {
        @Bean
        static BeanPostProcessor gatedPlanClientPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof StubEstimatePlanClient stub) {
                        return new EstimatePlanClient() {
                            @Override
                            public boolean isEnabled() {
                                return stub.isEnabled();
                            }

                            @Override
                            public PlanResponse plan(PlanRequest request) {
                                String user = request.userInstruction();
                                if (user != null && user.contains(BLOCK_MARKER)) {
                                    entered.countDown();
                                    try {
                                        if (!gate.await(60, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("gate not released");
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                return stub.plan(request);
                            }
                        };
                    }
                    return bean;
                }
            };
        }
    }

    @BeforeEach
    void seedCatalog() {
        gate = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        for (String code : List.of("bedroom", "bathroom", "kitchen")) {
            if (count("SELECT COUNT(*) FROM room_types WHERE code = ?", code) == 0) {
                tx().executeWithoutResult(s -> {
                    RoomTypeEntity rt = new RoomTypeEntity();
                    rt.setCode(code);
                    rt.setNameRU(code);
                    rt.setNamePL(code);
                    rt.setActive(true);
                    roomTypeDao.save(rt);
                });
            }
        }
        if (count("SELECT COUNT(*) FROM work_items WHERE code = ?", WORK_CODE) == 0) {
            tx().executeWithoutResult(s -> {
                MeasurementUnitEntity unit = szt();
                WorkCategoryEntity category = new WorkCategoryEntity();
                category.setCode("000-AIIT");
                category.setOrderNo(-1000);
                category.setNameRU("AI IT");
                category.setNamePL("AI IT");
                category.setActive(true);
                entityManager.persist(category);
                WorkItemEntity item = new WorkItemEntity();
                item.setWorkCategory(category);
                item.setUnit(unit);
                item.setCode(WORK_CODE);
                item.setNameRU("AI IT work");
                item.setNamePL("AI IT work");
                item.setActive(true);
                entityManager.persist(item);
                CurrencyEntity pln = currencyDao.findByCode("PLN").orElseGet(() -> {
                    CurrencyEntity c = new CurrencyEntity();
                    c.setCode("PLN");
                    c.setSymbol("zl");
                    c.setNameRU("Zloty");
                    c.setNamePL("Zloty");
                    c.setActive(true);
                    return currencyDao.save(c);
                });
                WorkPriceEntity price = new WorkPriceEntity();
                price.setWorkItem(item);
                price.setCurrency(pln);
                price.setNetPrice(new BigDecimal("25.00"));
                entityManager.persist(price);
            });
        }
        // one material norm on the AI work, so the offer cells carry a material line
        if (count("SELECT COUNT(*) FROM work_material_consumptions c JOIN work_items w ON w.id = c.work_item_id "
                + "WHERE w.code = ?", WORK_CODE) == 0) {
            tx().executeWithoutResult(s -> {
                MeasurementUnitEntity unit = szt();
                WorkItemEntity work = entityManager.createQuery(
                        "select w from WorkItemEntity w where w.code = :c", WorkItemEntity.class)
                        .setParameter("c", WORK_CODE).getSingleResult();
                ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
                type.setCode(MATERIAL_TYPE_CODE);
                type.setNameRU("AI IT material");
                type.setNamePL("AI IT material");
                type.setActive(true);
                entityManager.persist(type);
                WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
                c.setWorkItem(work);
                c.setMaterialUnit(unit);
                c.setBranch(ConsumptionBranch.construction);
                c.setConstructionMaterialType(type);
                c.setNormQty(new BigDecimal("2"));
                c.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
                c.setSourceType("TEST");
                c.setSourceDoc("test");
                c.setSourceRef("test");
                entityManager.persist(c);
            });
        }
    }

    @AfterEach
    void releaseGate() {
        gate.countDown();
    }

    private MeasurementUnitEntity szt() {
        return entityManager.createQuery("select u from MeasurementUnitEntity u where lower(u.code) = 'szt'",
                MeasurementUnitEntity.class).getResultStream().findFirst().orElseGet(() -> {
                    MeasurementUnitEntity u = new MeasurementUnitEntity();
                    u.setCode("szt");
                    u.setNameRU("szt");
                    u.setNamePL("szt");
                    u.setActive(true);
                    entityManager.persist(u);
                    return u;
                });
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long startEstimateOk(long projectId, String prompt, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders
                .multipart("/api/rooms/project/" + projectId + "/autoimport");
        b.param("mode", "ESTIMATE").param("prompt", prompt);
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        b.header("Authorization", bearer("ADMIN"));
        MvcResult r = mockMvc.perform(b).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.jobId")).longValue();
    }

    private List<String> stageLog(long jobId) {
        String json = jdbc.queryForObject("SELECT stage_log::text FROM rooms_import_jobs WHERE id = ?",
                String.class, jobId);
        return JsonPath.read(json, "$[*].stage");
    }

    /** Live estimate lines of the AI catalog work in the project. */
    private int aiWorkLines(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_lines l JOIN estimates e ON e.id = l.estimate_id "
                + "JOIN work_items w ON w.id = l.work_item_id WHERE e.project_id = ? AND w.code = ?",
                projectId, WORK_CODE);
    }

    private int liveMaterialLines(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_materials m "
                + "JOIN estimate_line_room_qty q ON q.id = m.room_qty_id "
                + "JOIN estimate_lines l ON l.id = q.line_id JOIN estimates e ON e.id = l.estimate_id "
                + "JOIN work_items w ON w.id = l.work_item_id WHERE e.project_id = ? AND w.code = ?",
                projectId, WORK_CODE);
    }

    private OfferSnapshot snapshot(long offerId) {
        return tx().execute(s -> entityManager.find(OfferEntity.class, offerId).getSnapshot());
    }

    private static long snapshotMaterialCount(OfferSnapshot snapshot) {
        return snapshot.groups().stream().flatMap(g -> g.rows().stream()).flatMap(r -> r.cells().stream())
                .filter(c -> c.materials() != null).mapToLong(c -> c.materials().size()).sum();
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static long snapshotAiMarkCount(OfferSnapshot snapshot) {
        return snapshot.groups().stream().flatMap(g -> g.rows().stream()).flatMap(r -> r.cells().stream())
                .filter(c -> c.ai() != null).count();
    }

    private int aiWorkCells(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_qty q JOIN estimate_lines l ON l.id = q.line_id "
                + "JOIN estimates e ON e.id = l.estimate_id JOIN work_items w ON w.id = l.work_item_id "
                + "WHERE e.project_id = ? AND w.code = ?", projectId, WORK_CODE);
    }

    private int aiAnnotatedCells(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_qty q JOIN estimate_lines l ON l.id = q.line_id "
                + "JOIN estimates e ON e.id = l.estimate_id JOIN work_items w ON w.id = l.work_item_id "
                + "WHERE e.project_id = ? AND w.code = ? AND q.ai_meta IS NOT NULL", projectId, WORK_CODE);
    }

    @Test
    @DisplayName("ESTIMATE job: stages in order, rooms saved first, project locked meanwhile, lines + materials + "
            + "ai_meta + summary land in the estimate with no offer; a later offer copies the summary, "
            + "snapshots marks and materials, restores with parity and stays hidden from a client")
    void estimateJobEndToEnd() throws Exception {
        long projectId = createProject("e2e");
        addMember("CLIENT", projectId);
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS " + BLOCK_MARKER));

        // --- held inside PLANNING_WORKS: rooms already saved, nothing assigned, no offer, project locked
        assertThat(entered.await(60, TimeUnit.SECONDS)).as("planner reached").isTrue();
        String running = awaitStatus(jobId, "PLANNING_WORKS");
        assertThat(JsonPath.<Object>read(running, "$.roomsSavedAt")).isNotNull();
        int saved = JsonPath.<Integer>read(running, "$.roomsCreated");
        assertThat(saved).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM rooms WHERE project_id = ? AND ai_imported", projectId))
                .isEqualTo(saved);
        assertThat(count("SELECT COUNT(*) FROM offers WHERE project_id = ?", projectId)).isZero();
        assertThat(aiWorkCells(projectId)).isZero();
        assertThat(stageLog(jobId)).startsWith("EXTRACTING", "DETECTING_ROOMS", "CLASSIFYING", "MEASURING",
                "VALIDATING", "SAVING_ROOMS");
        MvcResult locked = postJson("/api/estimates/project/" + projectId + "/assignments", "{\"edits\":[]}");
        assertThat(locked.getResponse().getStatus()).as(locked.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(locked.getResponse().getContentAsString()).contains("error.project.ai.locked");
        assertThat(JsonPath.<Number>read(locked.getResponse().getContentAsString(), "$.params.jobId").longValue())
                .isEqualTo(jobId);
        MvcResult roomsRead = mockMvc.perform(get("/api/estimates/project/" + projectId + "/matrix")
                .header("Authorization", bearer("ADMIN"))).andReturn();
        assertThat(roomsRead.getResponse().getContentAsString()).doesNotContain("error.project.ai.locked");

        gate.countDown();
        String body = awaitTerminal(jobId);

        // --- completion: stages, counters, no offer
        assertThat(JsonPath.<String>read(body, "$.status")).as(body).isEqualTo("COMPLETED");
        assertThat(stageLog(jobId)).containsExactly("EXTRACTING", "DETECTING_ROOMS", "CLASSIFYING", "MEASURING",
                "VALIDATING", "SAVING_ROOMS", "PLANNING_WORKS", "ASSIGNING", "FINALIZING");
        assertThat(JsonPath.<Integer>read(body, "$.roomsCreated")).isEqualTo(saved);
        int worksDone = JsonPath.<Integer>read(body, "$.worksDone");
        assertThat(worksDone).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.result.estimate")).doesNotContainKey("offerId");
        assertThat(count("SELECT COUNT(*) FROM offers WHERE project_id = ?", projectId)).isZero();

        // --- the live estimate holds the AI work with materials, ai_meta per cell and the summary
        assertThat(aiWorkLines(projectId)).isEqualTo(1);
        assertThat(aiWorkCells(projectId)).isEqualTo(worksDone);
        assertThat(aiAnnotatedCells(projectId)).isEqualTo(worksDone);
        int liveMaterials = liveMaterialLines(projectId);
        assertThat(liveMaterials).as("a material line per cell").isGreaterThanOrEqualTo(worksDone);
        assertThat(jdbc.queryForObject("SELECT ai_summary->>'jobId' FROM estimates WHERE project_id = ?",
                String.class, projectId)).isEqualTo(String.valueOf(jobId));

        // --- the lock is gone
        MvcResult unlocked = postJson("/api/estimates/project/" + projectId + "/assignments", "{\"edits\":[]}");
        assertThat(unlocked.getResponse().getContentAsString()).doesNotContain("error.project.ai.locked");

        // --- notification exactly once, for the starter, linking to the Estimate tab
        awaitNotifications("ADMIN", "notification.estimateAi.completed", 1);
        Thread.sleep(1000);
        assertThat(notifications("ADMIN", "notification.estimateAi.completed")).hasSize(1);
        assertThat(notifications("ADMIN", "notification.estimateAi.completed").get(0).getDeepLink())
                .isEqualTo("/projects/" + projectId + "/estimate");

        // --- a later offer copies the summary and is flagged AI
        MvcResult prepared = postJson("/api/offers/project/" + projectId + "/prepare", "{}");
        assertThat(prepared.getResponse().getStatus()).as(prepared.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM offers WHERE project_id = ?", projectId)).isEqualTo(1);
        long offerId = jdbc.queryForObject("SELECT id FROM offers WHERE project_id = ?", Long.class, projectId);
        Map<String, Object> offer = jdbc.queryForMap(
                "SELECT status, ai_generated, ai_report->>'jobId' AS report_job FROM offers WHERE id = ?", offerId);
        assertThat(offer.get("status")).isEqualTo("DRAFT");
        assertThat(offer.get("ai_generated")).isEqualTo(true);
        assertThat(offer.get("report_job")).isEqualTo(String.valueOf(jobId));
        assertThat(jdbc.queryForObject("SELECT ai_report->>'generatedAt' FROM offers WHERE id = ?", String.class,
                offerId)).isEqualTo(jdbc.queryForObject(
                        "SELECT ai_summary->>'generatedAt' FROM estimates WHERE project_id = ?", String.class,
                        projectId));

        // --- the snapshot keeps the marks and the material lines
        OfferSnapshot snapshot = snapshot(offerId);
        assertThat(snapshot).isNotNull();
        assertThat(snapshotAiMarkCount(snapshot)).as("AI marks in the snapshot").isEqualTo(worksDone);
        assertThat(snapshotMaterialCount(snapshot)).as("material lines in the snapshot").isEqualTo(liveMaterials);

        // --- restore parity: the live estimate ends with the same AI work and material lines
        MvcResult restore = postJson("/api/offers/" + offerId + "/restore", "{}");
        assertThat(restore.getResponse().getStatus()).as(restore.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(restore.getResponse().getContentAsString(), "$.skippedMaterials"))
                .isZero();
        assertThat(aiWorkLines(projectId)).isEqualTo(1);
        assertThat(aiWorkCells(projectId)).isEqualTo(worksDone);
        assertThat(liveMaterialLines(projectId)).isEqualTo(liveMaterials);

        // --- the offer is not visible to a client
        MvcResult client = mockMvc.perform(get("/api/offers/" + offerId)
                .header("Authorization", bearer("CLIENT"))).andReturn();
        assertThat(client.getResponse().getStatus()).as(client.getResponse().getContentAsString()).isEqualTo(404);
    }
}
