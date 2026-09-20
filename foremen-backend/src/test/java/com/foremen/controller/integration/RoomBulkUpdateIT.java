package com.foremen.controller.integration;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AdminService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.RoomService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the generic heterogeneous bulk-update endpoint {@code PUT /api/rooms/bulk}
 * (FOR-05-02, tasks 1.1/1.2) as exercised for the room vertical, and for the {@code RoomService}
 * per-field manual override on a geometry room (task 2.1).
 *
 * <p>Covers:
 * <ul>
 *   <li><b>Requirement 6.2</b> — a single {@code PUT /api/rooms/bulk} call applies a list of
 *       <em>heterogeneous</em> per-row updates (each row gets its own values, not one model applied
 *       to many ids), preserving order, and persisting each row independently.</li>
 *   <li><b>Requirement 5.2 / 5.3 / 5.4</b> — a room <em>with geometry</em> whose bulk-update payload
 *       sends one metric as an explicit value + {@code MANUAL} source ends with exactly that field
 *       {@code MANUAL} and the other four geometry-derived metrics {@code CALCULATED}.</li>
 *   <li><b>Requirement 6.2 (atomicity)</b> — when one row in the batch is invalid (an out-of-range
 *       manual metric), the whole {@code @Transactional} batch rolls back and <em>nothing</em> is
 *       persisted, not even the valid rows.</li>
 *   <li><b>Requirement 6.7</b> — the bulk update reuses the shipped project-scoped guard: a caller
 *       may only update rooms in projects they may access; a targeted room in a project the caller
 *       is not a member of is denied (as the {@code Access_Denied_Outcome}: 404
 *       {@code error.entity.not.found}) and the whole batch is aborted with no mutation.</li>
 * </ul>
 *
 * <p>Container/profile setup mirrors {@link RoomManualMetricsIT}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code @ActiveProfiles("integration-test")} (Liquibase
 * disabled, Hibernate {@code create-drop} builds the schema), {@code @WithMockUser(roles="ADMIN")}
 * for the HTTP-layer scenarios (ADMIN bypasses the ABAC matrix + project scoping, so no seeded
 * {@code ROOMS} resource row is required).
 *
 * <p><b>No class-level {@code @Transactional}.</b> Unlike {@link RoomCrudIT}, this suite must observe
 * <em>real</em> commit/rollback across the batch endpoint's own {@code @Transactional} boundary — a
 * test-method transaction would make the service transaction merely join it, so a failed batch would
 * only be marked rollback-only (its dirty writes still visible to same-transaction reads) rather than
 * actually rolled back. Running without a wrapping transaction lets each MockMvc call commit or roll
 * back genuinely; isolation instead comes from the {@code create-drop} schema plus per-run unique
 * suffixes on every seeded row.
 *
 * <p>The three ADMIN scenarios drive the real HTTP endpoint through {@link MockMvc}. The
 * project-scoping scenario (Requirement 6.7) instead drives {@link RoomService#update(List)}
 * directly under a manually-installed <em>non-ADMIN</em> principal with seeded
 * {@code project_members} — the exact per-row method the endpoint delegates to and where the
 * shipped {@link com.foremen.service.ProjectScopedService#assertProjectAccess(Object)} guard fires
 * — mirroring {@link com.foremen.scoping.RoomScopingIT}. Exercising the guard through MockMvc would
 * additionally require seeding the {@code ROOMS}/{@code UPDATE} ABAC grant for the caller's role,
 * which is orthogonal to what this scenario proves.
 *
 * <p>Validates: Requirements 5.2, 6.2, 6.7
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class RoomBulkUpdateIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON converter's text value
            // into the jsonb geometry column, matching the deployed app.
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /** Per-run unique suffix so seeded reference rows never collide across runs. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomDao roomDao;

    @Autowired
    private RoomTypeDao roomTypeDao;

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 6.2 : heterogeneous per-row updates persist per row (order preserved)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("PUT /api/rooms/bulk - heterogeneous per-row updates each persist their own values")
    void bulkUpdate_heterogeneousRows_persistPerRow() throws Exception {
        long projectId = persistProject(ProjectStatus.DRAFT);
        long roomTypeId = persistRoomType();
        long roomA = createManualRoom(projectId, roomTypeId, "A-before", "10.00");
        long roomB = createManualRoom(projectId, roomTypeId, "B-before", "20.00");

        // Two DISTINCT payloads in one batch — each row gets its own label + floorArea (not one
        // model applied to both). Order is [roomA, roomB].
        String body = """
                [
                  { "id": %d, "data": { "projectId": %d, "roomTypeId": %d, "label": "A-after", "floorArea": 11.11 } },
                  { "id": %d, "data": { "projectId": %d, "roomTypeId": %d, "label": "B-after", "floorArea": 22.22 } }
                ]
                """.formatted(roomA, projectId, roomTypeId, roomB, projectId, roomTypeId);

        mockMvc.perform(put("/api/rooms/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // response list preserves request order and carries per-row values
                .andExpect(jsonPath("$[0].id").value((int) roomA))
                .andExpect(jsonPath("$[0].label").value("A-after"))
                .andExpect(jsonPath("$[0].floorArea").value(11.11))
                .andExpect(jsonPath("$[1].id").value((int) roomB))
                .andExpect(jsonPath("$[1].label").value("B-after"))
                .andExpect(jsonPath("$[1].floorArea").value(22.22));

        // Each row persisted independently in the DB.
        RoomEntity persistedA = roomDao.findById(roomA).orElseThrow();
        assertThat(persistedA.getLabel()).isEqualTo("A-after");
        assertThat(persistedA.getFloorArea()).isEqualByComparingTo("11.11");
        assertThat(persistedA.getFloorAreaSource()).isEqualTo(MeasureSource.MANUAL);

        RoomEntity persistedB = roomDao.findById(roomB).orElseThrow();
        assertThat(persistedB.getLabel()).isEqualTo("B-after");
        assertThat(persistedB.getFloorArea()).isEqualByComparingTo("22.22");
        assertThat(persistedB.getFloorAreaSource()).isEqualTo(MeasureSource.MANUAL);
    }

    // ------------------------------------------------------------------
    // 5.2 / 5.3 / 5.4 : geometry room + one MANUAL override -> that field MANUAL, rest CALCULATED
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("PUT /api/rooms/bulk - geometry room with one overridden metric -> that field MANUAL, rest CALCULATED")
    void bulkUpdate_geometryRoomWithOneOverride_marksOnlyThatFieldManual() throws Exception {
        long projectId = persistProject(ProjectStatus.DRAFT);
        long roomTypeId = persistRoomType();
        long roomId = createManualRoom(projectId, roomTypeId, "geometry-room", "5.00");

        // Update sends geometry (a 4x3 rectangle -> floorArea 12, perimeter 14, wallArea 42) AND a
        // single per-field override: floorArea = 99.99 with source MANUAL. Only floorArea must stay
        // MANUAL/99.99; the other four metrics recompute from geometry and are CALCULATED.
        String body = """
                [
                  {
                    "id": %d,
                    "data": {
                      "projectId": %d,
                      "roomTypeId": %d,
                      "label": "geometry-room",
                      "ceilingHeight": 3.00,
                      "geometry": {
                        "vertices": [
                          {"x": 0, "y": 0},
                          {"x": 4, "y": 0},
                          {"x": 4, "y": 3},
                          {"x": 0, "y": 3}
                        ],
                        "walls": [ {}, {}, {}, {} ]
                      },
                      "floorArea": 99.99,
                      "floorAreaSource": "MANUAL"
                    }
                  }
                ]
                """.formatted(roomId, projectId, roomTypeId);

        mockMvc.perform(put("/api/rooms/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value((int) roomId))
                // overridden field kept as supplied
                .andExpect(jsonPath("$[0].floorArea").value(99.99))
                // the rest recomputed from geometry
                .andExpect(jsonPath("$[0].perimeter").value(14.00))
                .andExpect(jsonPath("$[0].wallArea").value(42.00))
                .andExpect(jsonPath("$[0].doorArea").value(0.00))
                .andExpect(jsonPath("$[0].windowArea").value(0.00));

        // Read DTO exposes source per metric.
        mockMvc.perform(get("/api/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorArea.value").value(99.99))
                .andExpect(jsonPath("$.floorArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.perimeter.source").value("CALCULATED"))
                .andExpect(jsonPath("$.wallArea.source").value("CALCULATED"))
                .andExpect(jsonPath("$.doorArea.source").value("CALCULATED"))
                .andExpect(jsonPath("$.windowArea.source").value("CALCULATED"));

        // In-DB: exactly floorArea is MANUAL; the other four are CALCULATED and geometry-derived.
        RoomEntity persisted = roomDao.findById(roomId).orElseThrow();
        assertThat(persisted.getGeometry()).as("geometry persisted").isNotNull();
        assertThat(persisted.getFloorArea()).isEqualByComparingTo("99.99");
        assertThat(persisted.getFloorAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getPerimeter()).isEqualByComparingTo("14.00");
        assertThat(persisted.getPerimeterSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getWallArea()).isEqualByComparingTo("42.00");
        assertThat(persisted.getWallAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getDoorArea()).isEqualByComparingTo("0.00");
        assertThat(persisted.getDoorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getWindowArea()).isEqualByComparingTo("0.00");
        assertThat(persisted.getWindowAreaSource()).isEqualTo(MeasureSource.CALCULATED);
    }

    // ------------------------------------------------------------------
    // 6.2 (atomicity) : one invalid row rolls back the whole batch
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("PUT /api/rooms/bulk - one invalid row rolls back the whole batch (nothing persisted)")
    void bulkUpdate_oneInvalidRow_rollsBackWholeBatch() throws Exception {
        long projectId = persistProject(ProjectStatus.DRAFT);
        long roomTypeId = persistRoomType();
        long roomA = createManualRoom(projectId, roomTypeId, "A-before", "10.00");
        long roomB = createManualRoom(projectId, roomTypeId, "B-before", "20.00");

        // Row 0 (roomA) is valid; row 1 (roomB) carries an out-of-range manual floorArea
        // (> 9999999999.99) which RoomService.normalize rejects with a 400 before persistence,
        // aborting the shared @Transactional batch.
        String body = """
                [
                  { "id": %d, "data": { "projectId": %d, "roomTypeId": %d, "label": "A-after", "floorArea": 11.11 } },
                  { "id": %d, "data": { "projectId": %d, "roomTypeId": %d, "label": "B-after", "floorArea": 99999999999.99 } }
                ]
                """.formatted(roomA, projectId, roomTypeId, roomB, projectId, roomTypeId);

        mockMvc.perform(put("/api/rooms/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        // Each DAO read below runs in its own transaction, so it observes the COMMITTED state after
        // the failed batch rolled back.
        // The whole batch rolled back: the VALID row (roomA) is unchanged too.
        RoomEntity persistedA = roomDao.findById(roomA).orElseThrow();
        assertThat(persistedA.getLabel()).isEqualTo("A-before");
        assertThat(persistedA.getFloorArea()).isEqualByComparingTo("10.00");

        RoomEntity persistedB = roomDao.findById(roomB).orElseThrow();
        assertThat(persistedB.getLabel()).isEqualTo("B-before");
        assertThat(persistedB.getFloorArea()).isEqualByComparingTo("20.00");
    }

    // ------------------------------------------------------------------
    // 6.7 : project-scoping/ABAC denies rooms outside the caller's projects
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Bulk update: a non-member caller is denied a room outside their projects; whole batch aborted (6.7)")
    void bulkUpdate_nonMemberRoom_deniedAndBatchAborted() {
        long projectTypeId = persistRoomType();
        RoleEntity role = seedRole();

        // The caller is a member of project IN, but NOT of project OUT.
        long projectIn = persistProject(ProjectStatus.DRAFT);
        long projectOut = persistProject(ProjectStatus.DRAFT);
        long roomIn = seedRoom(projectIn, projectTypeId, "room-in", "10.00");
        long roomOut = seedRoom(projectOut, projectTypeId, "room-out", "20.00");

        UserEntity user = seedUser(role);
        saveMembership(user, projectIn, role);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        // A batch touching an in-scope room AND an out-of-scope room. The per-row project-scope
        // guard denies the out-of-scope row (404 error.entity.not.found), aborting the whole batch.
        List<AdminService.IdModel<Long, RoomServiceExtendedModel>> batch = List.of(
                new AdminService.IdModel<>(roomIn, modelWithLabel(projectIn, projectTypeId, "in-after")),
                new AdminService.IdModel<>(roomOut, modelWithLabel(projectOut, projectTypeId, "out-after")));

        ForemenApiException ex = catchThrowableOfType(() -> roomService.update(batch), ForemenApiException.class);
        assertThat(ex).as("expected the out-of-scope row to be denied").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");

        // Nothing was mutated: neither the in-scope nor the out-of-scope room changed.
        assertThat(roomDao.findById(roomIn).orElseThrow().getLabel()).isEqualTo("room-in");
        assertThat(roomDao.findById(roomOut).orElseThrow().getLabel()).isEqualTo("room-out");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String unique(String prefix) {
        return prefix + "-" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    /** Persists a project (committed via the DAO) with the given status and returns its id. */
    private long persistProject(ProjectStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setName(unique("bulk-project"));
        project.setStatus(status);
        return projectDao.save(project).getId();
    }

    /** Persists an active room type (committed via the DAO) and returns its id. */
    private long persistRoomType() {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode(unique("RT"));
        roomType.setNameRU("Тип");
        roomType.setNamePL("Typ");
        roomType.setActive(true);
        return roomTypeDao.save(roomType).getId();
    }

    /**
     * Creates a room through the generic {@code POST /api/rooms} endpoint with no geometry and a
     * directly-supplied manual {@code floorArea} (kept as {@code MANUAL}), returning its id.
     */
    private long createManualRoom(long projectId, long roomTypeId, String label, String floorArea) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": %d, "roomTypeId": %d, "label": "%s", "floorArea": %s }
                                """.formatted(projectId, roomTypeId, label, floorArea)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Persists a room directly via the DAO (no geometry, manual floorArea) and returns its id. */
    private long seedRoom(long projectId, long roomTypeId, String label, String floorArea) {
        RoomEntity room = new RoomEntity();
        room.setProject(projectDao.findById(projectId).orElseThrow());
        room.setRoomType(roomTypeDao.findById(roomTypeId).orElseThrow());
        room.setLabel(label);
        room.setFloorArea(new BigDecimal(floorArea));
        room.setFloorAreaSource(MeasureSource.MANUAL);
        return roomDao.save(room).getId();
    }

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode(unique("BULK-ROLE"));
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser(RoleEntity role) {
        UserEntity u = new UserEntity();
        u.setName("Bulk Update Test User");
        u.setEmail("bulk-update+" + System.nanoTime() + COUNTER.incrementAndGet() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private void saveMembership(UserEntity user, long projectId, RoleEntity role) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** A minimal update model targeting a room in {@code projectId} with a new {@code label}. */
    private RoomServiceExtendedModel modelWithLabel(long projectId, long roomTypeId, String label) {
        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(projectId);
        model.setRoomTypeId(roomTypeId);
        model.setLabel(label);
        return model;
    }
}
