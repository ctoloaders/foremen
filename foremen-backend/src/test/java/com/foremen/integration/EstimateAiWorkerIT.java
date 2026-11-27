package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.estimateai.AiJobContext;
import com.foremen.service.estimateai.AssignWorkService;
import com.foremen.service.estimateai.ai.EstimatePlanClient;
import com.foremen.service.estimateai.ai.StubEstimatePlanClient;
import com.jayway.jsonpath.JsonPath;

import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * FOR-05-04 task 3.9 - the {@code ESTIMATE} job end to end against the real schema with the stub vision and
 * the stub planner: stage sequence, result hand-off ({@code result.estimate}), staged persistence on cancel and
 * on failure, {@code useExistingRooms}, the single-active-job rule and the unchanged {@code ROOMS_ONLY} path.
 *
 * <p>Repeatability: generator strategy. Every test creates its own project (names carry the per-instance run
 * id) and prompts carry the run id. The one catalog work item used by the stub planner is created once under
 * a fixed code and reused (it sorts first in the catalog, so the stub picks it).
 *
 * <p>Validates: Requirements 2.1-2.7, 3.5, 4.4, 4.6, 4.7, 6.3, 18.2, 18.5
 */
@TestPropertySource(properties = {
        "foremen.estimate-ai.enabled=true",
        "foremen.estimate-ai.llm.stub=true"
})
@Import(EstimateAiWorkerIT.GateConfig.class)
class EstimateAiWorkerIT extends RoomsAutoimportItBase {

    private static final String WORK_CODE = "000-AIIT-WORK";
    private static final String BLOCK_MARKER = "TEST:BLOCK";

    /** The planner of a prompt carrying {@value #BLOCK_MARKER} waits here until the test releases it. */
    static volatile CountDownLatch gate = new CountDownLatch(1);
    static volatile CountDownLatch entered = new CountDownLatch(1);

    /** Holds a job inside {@code ASSIGNING}: the second assign call of a gated project waits for the test. */
    static volatile Long blockProjectId;
    static volatile CountDownLatch assignGate = new CountDownLatch(1);
    static volatile CountDownLatch assignEntered = new CountDownLatch(1);
    static final Map<Long, Integer> ASSIGN_CALLS = new ConcurrentHashMap<>();

    @MockitoSpyBean private AssignWorkService assignWorkSpy;
    @Autowired private CurrencyDao currencyDao;
    @Autowired private com.foremen.service.EstimateService estimateService;

    /** Wraps the stub planner so a test can hold a job inside {@code PLANNING_WORKS}. */
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
    void seedCatalogAndRoomTypes() {
        gate = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        assignGate = new CountDownLatch(1);
        assignEntered = new CountDownLatch(1);
        blockProjectId = null;
        Mockito.doAnswer(inv -> {
            AiJobContext c = inv.getArgument(0);
            if (c != null && c.projectId() != null && c.projectId().equals(blockProjectId)
                    && ASSIGN_CALLS.merge(c.jobId(), 1, Integer::sum) == 2) {
                assignEntered.countDown();
                if (!assignGate.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("assign gate not released");
                }
            }
            return inv.callRealMethod();
        }).when(assignWorkSpy).assign(Mockito.any(), Mockito.any());
        for (String code : List.of("bedroom", "bathroom", "kitchen")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM room_types WHERE code = ?", Integer.class, code) == 0) {
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
        if (jdbc.queryForObject("SELECT COUNT(*) FROM work_items WHERE code = ?", Integer.class, WORK_CODE) == 0) {
            tx().executeWithoutResult(s -> {
                MeasurementUnitEntity unit = entityManager.createQuery(
                        "select u from MeasurementUnitEntity u where lower(u.code) = 'szt'",
                        MeasurementUnitEntity.class).getResultStream().findFirst().orElseGet(() -> {
                            MeasurementUnitEntity u = new MeasurementUnitEntity();
                            u.setCode("szt");
                            u.setNameRU("szt");
                            u.setNamePL("szt");
                            u.setActive(true);
                            entityManager.persist(u);
                            return u;
                        });
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
    }

    @AfterEach
    void releaseGate() {
        gate.countDown();
        assignGate.countDown();
        blockProjectId = null;
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult startEstimate(long projectId, String prompt, Map<String, String> extra,
                                    MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/rooms/project/" + projectId + "/autoimport");
        b.param("mode", "ESTIMATE");
        if (prompt != null) {
            b.param("prompt", prompt);
        }
        extra.forEach(b::param);
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        b.header("Authorization", bearer("ADMIN"));
        return mockMvc.perform(b).andReturn();
    }

    private long startEstimateOk(long projectId, String prompt, Map<String, String> extra, MockMultipartFile... files)
            throws Exception {
        MvcResult r = startEstimate(projectId, prompt, extra, files);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.jobId")).longValue();
    }

    private List<String> stageLog(long jobId) {
        String json = jdbc.queryForObject("SELECT stage_log::text FROM rooms_import_jobs WHERE id = ?",
                String.class, jobId);
        return JsonPath.read(json, "$[*].stage");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int offers(long projectId) {
        return count("SELECT COUNT(*) FROM offers WHERE project_id = ?", projectId);
    }

    /** Live estimate lines of the AI catalog work. */
    private int aiWorkLines(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_lines l JOIN estimates e ON e.id = l.estimate_id "
                + "JOIN work_items w ON w.id = l.work_item_id WHERE e.project_id = ? AND w.code = ?",
                projectId, WORK_CODE);
    }

    /** Cells (line x room) of the AI catalog work. */
    private int aiWorkCells(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_qty q JOIN estimate_lines l ON l.id = q.line_id "
                + "JOIN estimates e ON e.id = l.estimate_id JOIN work_items w ON w.id = l.work_item_id "
                + "WHERE e.project_id = ? AND w.code = ?", projectId, WORK_CODE);
    }

    /** Cells of the AI catalog work that carry an {@code ai_meta} annotation. */
    private int aiWorkAnnotatedCells(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_qty q JOIN estimate_lines l ON l.id = q.line_id "
                + "JOIN estimates e ON e.id = l.estimate_id JOIN work_items w ON w.id = l.work_item_id "
                + "WHERE e.project_id = ? AND w.code = ? AND q.ai_meta IS NOT NULL", projectId, WORK_CODE);
    }

    private int aiSummaries(long projectId) {
        return count("SELECT COUNT(*) FROM estimates WHERE project_id = ? AND ai_summary IS NOT NULL", projectId);
    }

    private int aiRooms(long projectId) {
        return count("SELECT COUNT(*) FROM rooms WHERE project_id = ? AND ai_imported", projectId);
    }

    private static final List<String> ROOMS_PHASE = List.of("EXTRACTING", "DETECTING_ROOMS", "CLASSIFYING",
            "MEASURING", "VALIDATING");

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("ESTIMATE job runs every stage in order, assigns straight into the estimate and creates no offer")
    void stageSequenceWithStub() throws Exception {
        long projectId = createProject("est-ok");
        seedRoom(projectId, "Existing", "10.00");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS"), Map.of());

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).as(body).isEqualTo("COMPLETED");
        assertThat(JsonPath.<String>read(body, "$.mode")).isEqualTo("ESTIMATE");
        assertThat(JsonPath.<String>read(body, "$.phase")).isEqualTo("ESTIMATE");
        assertThat(JsonPath.<Object>read(body, "$.roomsSavedAt")).isNotNull();
        int created = JsonPath.<Integer>read(body, "$.roomsCreated");
        assertThat(created).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.result.estimate")).doesNotContainKey("offerId");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKey("offerId");
        assertThat(JsonPath.<Integer>read(body, "$.result.estimate.worksDone")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Object>read(body, "$.result.estimate.report.summary")).isNotNull();

        assertThat(stageLog(jobId)).containsExactly("EXTRACTING", "DETECTING_ROOMS", "CLASSIFYING", "MEASURING",
                "VALIDATING", "SAVING_ROOMS", "PLANNING_WORKS", "ASSIGNING", "FINALIZING");
        // counters and action log of the poll DTO
        int worksDone = JsonPath.<Integer>read(body, "$.worksDone");
        assertThat(worksDone).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.worksTotal")).isEqualTo(worksDone);
        assertThat(JsonPath.<Integer>read(body, "$.roomsTotal")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.roomsDone"))
                .isEqualTo(JsonPath.<Integer>read(body, "$.roomsTotal"));
        List<String> codes = JsonPath.read(body, "$.actionLog[*].code");
        assertThat(codes).contains("ROOM_MEASURED", "ROOM_SAVED", "PLAN_READY", "WORK_ASSIGNED", "SUMMARY_WRITTEN");
        assertThat(codes.get(0)).as("newest first").isEqualTo("SUMMARY_WRITTEN");
        // the lines are in the live estimate, one annotated cell per assignment, and the summary is stored
        assertThat(aiWorkLines(projectId)).isEqualTo(1);
        assertThat(aiWorkCells(projectId)).isEqualTo(worksDone);
        assertThat(aiWorkAnnotatedCells(projectId)).isEqualTo(worksDone);
        assertThat(aiSummaries(projectId)).isEqualTo(1);
        assertThat(offers(projectId)).isZero();
        assertThat(aiRooms(projectId)).isEqualTo(created);
    }

    @Test
    @DisplayName("assignments commit one by one: cells and ai_meta are visible between calls, the project stays "
            + "locked, cancel keeps the assigned lines and stops the loop")
    void assignmentsVisibleBetweenCallsAndCancelKeepsLines() throws Exception {
        long projectId = createProject("est-assign-cancel");
        seedRoom(projectId, "Existing", "10.00");
        blockProjectId = projectId;
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS"), Map.of());
        assertThat(assignEntered.await(60, TimeUnit.SECONDS)).as("second assign call reached").isTrue();

        // --- first assignment is committed and visible; the job is still ASSIGNING
        String running = getJob(jobId, bearer("ADMIN")).getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(running, "$.status")).isEqualTo("ASSIGNING");
        assertThat(JsonPath.<Integer>read(running, "$.worksDone")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(running, "$.worksTotal")).isGreaterThanOrEqualTo(2);
        assertThat(JsonPath.<List<String>>read(running, "$.actionLog[*].code")).contains("WORK_ASSIGNED");
        assertThat(aiWorkLines(projectId)).isEqualTo(1);
        assertThat(aiWorkCells(projectId)).isEqualTo(1);
        assertThat(aiWorkAnnotatedCells(projectId)).isEqualTo(1);
        assertThat(offers(projectId)).isZero();
        MvcResult locked = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/estimates/project/" + projectId + "/assignments")
                .header("Authorization", bearer("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"edits\":[]}")).andReturn();
        assertThat(locked.getResponse().getStatus()).as(locked.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(locked.getResponse().getContentAsString()).contains("error.project.ai.locked");

        // --- cancel: the assigned line stays, nothing more is assigned
        assertThat(cancel(jobId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assignGate.countDown();
        await("partial summary", () -> aiSummaries(projectId) == 1, 30_000);
        Thread.sleep(500);

        assertThat(aiWorkCells(projectId)).as("only the first assignment is kept").isEqualTo(1);
        assertThat(aiWorkAnnotatedCells(projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT ai_summary->>'incomplete' FROM estimates WHERE project_id = ?", String.class, projectId))
                .isEqualTo("true");
        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(offers(projectId)).isZero();
    }

    @Test
    @DisplayName("cancel after SAVING_ROOMS keeps the saved rooms, creates no offer and unlocks the project")
    void cancelAfterSavingRoomsKeepsRooms() throws Exception {
        long projectId = createProject("est-cancel");
        seedRoom(projectId, "Existing", "10.00");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS " + BLOCK_MARKER), Map.of());
        assertThat(entered.await(60, TimeUnit.SECONDS)).as("planner reached").isTrue();
        String running = awaitStatus(jobId, "PLANNING_WORKS");
        int saved = JsonPath.<Integer>read(running, "$.roomsCreated");
        assertThat(saved).isGreaterThanOrEqualTo(1);
        int roomsBefore = roomDao.findByProjectId(projectId).size();

        MvcResult cancelled = cancel(jobId, bearer("ADMIN"));
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(204);
        gate.countDown();
        Thread.sleep(1500); // the worker notices the cancellation at the next stage boundary

        assertThat(current(projectId, bearer("ADMIN")).getResponse().getStatus()).isEqualTo(204);
        assertThat(roomDao.findByProjectId(projectId)).hasSize(roomsBefore);
        assertThat(aiRooms(projectId)).isEqualTo(saved);
        assertThat(offers(projectId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?", projectId)).isZero();
    }

    @Test
    @DisplayName("a model outage fails the job with llm_unavailable; the saved rooms stay and no offer exists")
    void llmOutageFailsAfterRoomsSaved() throws Exception {
        long projectId = createProject("est-llm");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS STUB:FAIL"), Map.of());

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(body, "$.failureReason"))
                .isEqualTo("error.estimateai.failed.llm_unavailable");
        assertThat(JsonPath.<Integer>read(body, "$.roomsCreated")).isGreaterThanOrEqualTo(1);
        assertThat(aiRooms(projectId)).isEqualTo(JsonPath.<Integer>read(body, "$.roomsCreated"));
        assertThat(offers(projectId)).isZero();
    }

    @Test
    @DisplayName("an invalid plan twice fails the job with plan_invalid")
    void invalidPlanFails() throws Exception {
        long projectId = createProject("est-invalid");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS STUB:INVALID"), Map.of());

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.failureReason")).isEqualTo("error.estimateai.failed.plan_invalid");
        assertThat(offers(projectId)).isZero();
    }

    @Test
    @DisplayName("no room could be saved and none existed: no_rooms failure, nothing created")
    void noRoomsFails() throws Exception {
        long projectId = createProject("est-norooms");
        long jobId = startEstimateOk(projectId, prompt("STUB:UNKNOWN_TYPE"), Map.of());

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(body, "$.failureReason")).isEqualTo("error.estimateai.failed.no_rooms");
        assertThat(roomDao.findByProjectId(projectId)).isEmpty();
        assertThat(offers(projectId)).isZero();
    }

    @Test
    @DisplayName("an empty plan still completes: nothing assigned, no offer")
    void emptyPlanCompletes() throws Exception {
        long projectId = createProject("est-empty");
        seedRoom(projectId, "Existing", "10.00");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS STUB:EMPTY"), Map.of());

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).as(body).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Integer>read(body, "$.result.estimate.worksDone")).isZero();
        assertThat(aiWorkLines(projectId)).isZero();
        assertThat(offers(projectId)).isZero();
    }

    @Test
    @DisplayName("useExistingRooms skips the ROOMS phase and SAVING_ROOMS and plans over the existing rooms")
    void useExistingRooms() throws Exception {
        long projectId = createProject("est-existing");
        long room = seedRoom(projectId, "Existing", "12.00");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS"), Map.of("useExistingRooms", "true"));

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).as(body).isEqualTo("COMPLETED");
        assertThat(aiCalls("detect")).isZero();
        assertThat(aiCalls("measure")).isZero();
        assertThat(stageLog(jobId)).containsExactly("PLANNING_WORKS", "ASSIGNING", "FINALIZING");
        assertThat(roomDao.findByProjectId(projectId)).extracting(r -> r.getId()).containsExactly(room);
        assertThat(JsonPath.<Integer>read(body, "$.result.estimate.worksDone")).isEqualTo(1);
        assertThat(aiWorkCells(projectId)).isEqualTo(1);
        assertThat(JsonPath.<String>read(body, "$.phase")).isEqualTo("ESTIMATE");
    }

    @Test
    @DisplayName("useExistingRooms on a project without rooms is refused with 400 and nothing is stored")
    void useExistingRoomsWithoutRooms() throws Exception {
        long projectId = createProject("est-existing-none");

        MvcResult r = startEstimate(projectId, prompt("STUB:THREE_ROOMS"), Map.of("useExistingRooms", "true"));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("error.estimateai.no_rooms");
        assertThat(count("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?", projectId)).isZero();
    }

    @Test
    @DisplayName("a locked (non-DRAFT) estimate refuses the start with 409 error.estimate.locked")
    void lockedEstimateRefused() throws Exception {
        long projectId = createProject("est-locked");
        seedRoom(projectId, "Existing", "10.00");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("it", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"))));
        try {
            estimateService.getOrCreateForProject(projectId);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        jdbc.update("UPDATE estimates SET status = 'APPROVED' WHERE project_id = ?", projectId);

        MvcResult r = startEstimate(projectId, prompt("STUB:THREE_ROOMS"), Map.of());

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("error.estimate.locked");
        assertThat(count("SELECT COUNT(*) FROM rooms_import_jobs WHERE project_id = ?", projectId)).isZero();
    }

    @Test
    @DisplayName("an unknown mode is a 400")
    void unknownModeRefused() throws Exception {
        long projectId = createProject("est-badmode");
        MockMultipartHttpServletRequestBuilder b = multipart("/api/rooms/project/" + projectId + "/autoimport");
        b.param("mode", "BOGUS").param("prompt", prompt("STUB:THREE_ROOMS"));
        b.header("Authorization", bearer("ADMIN"));

        assertThat(mockMvc.perform(b).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a second start while an ESTIMATE job is active is refused with 429 in_progress")
    void inProgressRefused() throws Exception {
        long projectId = createProject("est-busy");
        seedRoom(projectId, "Existing", "10.00");
        long jobId = startEstimateOk(projectId, prompt("STUB:THREE_ROOMS " + BLOCK_MARKER), Map.of());
        assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();

        MvcResult second = startEstimate(projectId, prompt("STUB:THREE_ROOMS"), Map.of());
        MvcResult rooms = start(projectId, bearer("ADMIN"), prompt("STUB:THREE_ROOMS"));

        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(second.getResponse().getContentAsString()).contains("error.roomsimport.in_progress");
        assertThat(rooms.getResponse().getStatus()).as("the rule covers both modes").isEqualTo(429);

        gate.countDown();
        assertThat(JsonPath.<String>read(awaitTerminal(jobId), "$.status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("ROOMS_ONLY regression: default mode, draft result only, no rooms saved, no offer, no estimate stages")
    void roomsOnlyUnchanged() throws Exception {
        long projectId = createProject("rooms-only");
        long jobId = startOk(projectId, prompt("STUB:THREE_ROOMS"));

        String body = awaitTerminal(jobId);

        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<String>read(body, "$.mode")).isEqualTo("ROOMS_ONLY");
        assertThat(JsonPath.<String>read(body, "$.phase")).isEqualTo("ROOMS");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.result")).doesNotContainKey("estimate");
        assertThat(stageLog(jobId)).containsExactlyElementsOf(ROOMS_PHASE);
        assertThat(roomDao.findByProjectId(projectId)).isEmpty();
        assertThat(offers(projectId)).isZero();
    }
}
