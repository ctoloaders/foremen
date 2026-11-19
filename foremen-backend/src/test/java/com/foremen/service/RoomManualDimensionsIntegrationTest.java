package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.image.ImageStorage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-05-02c task 4.2 - the room 1701 case end to end over HTTP on the real Liquibase schema
 * (changeset 266 included) on a Testcontainers PostgreSQL: {@code POST /api/rooms/project/{id}/save}
 * with {@code wallGap = 2}, {@code finishGap = 3} (both {@code MANUAL}) on a rectangle with one DOOR
 * 0.58 x 2.0, then the persisted columns, the read model and the perimeter explanation are checked;
 * a second save with the read-back values keeps everything. A second test applies the changelog up to
 * (excluding) 266 on a separate database with existing rooms, applies 266, checks the {@code CALCULATED}
 * backfill and that a re-run changes nothing.
 *
 * <p>Repeatability: every project, user and room type carries a per-run id; both databases are fresh
 * per JVM run.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.5, 3.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=true",
        "foremen.public-base-url=http://localhost:3000"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class RoomManualDimensionsIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String CHANGESET_PREFIX = "266-";

    /** Schema of the Spring context: the full changelog. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    /** Separate database for the migration test (changelog stops right before 266). */
    static final PostgreSQLContainer<?> MIGRATION_DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        synchronized (RoomManualDimensionsIntegrationTest.class) {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
                migrateFully(POSTGRES);
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private RoomTypeDao roomTypeDao;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private ImageStorage imageStorage;

    private final ObjectMapper json = new ObjectMapper();
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);

    private String bearer;
    private long projectId;
    private long roomTypeId;

    @BeforeEach
    void setUp() {
        when(imageStorage.isConfigured()).thenReturn(true);
        when(imageStorage.storeObject(any(), anyString())).thenAnswer(inv -> "it/" + UUID.randomUUID());
        when(imageStorage.signedDownloadUrl(anyString(), anyLong())).thenReturn("https://signed.example/doc");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UserEntity admin = tx.execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("RoomDimIT " + runId);
            u.setEmail("room-dim-it+" + runId + "@example.com");
            u.setRole(roleDao.findByCode("ADMIN").orElseThrow());
            u.setActive(true);
            return userDao.save(u);
        });
        bearer = "Bearer " + jwt.generateAccessToken(admin.getId(), "ADMIN", admin.getEmail());
        projectId = tx.execute(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("RoomDimIT " + runId);
            p.setStatus(ProjectStatus.ACTIVE);
            entityManager.persist(p);
            entityManager.flush();
            return p.getId();
        });
        roomTypeId = tx.execute(s -> {
            RoomTypeEntity rt = new RoomTypeEntity();
            rt.setCode("ROOM-DIM-IT-" + runId);
            rt.setNameRU("Тип");
            rt.setNamePL("Typ");
            rt.setActive(true);
            return roomTypeDao.save(rt).getId();
        });
    }

    // ------------------------------------------------------------------ room 1701

    @Test
    @DisplayName("room 1701: manual gaps and derived door size are persisted, explained and survive a second save")
    void room1701SaveKeepsManualGapsAndDerivesDoorSize() throws Exception {
        // Original state: geometry only (no manual values) -> gaps 0, wall area 26.43, CALCULATED stamps.
        long roomId = createRoom();
        JsonNode created = readRoom(roomId);
        assertThat(created.path("wallGap").decimalValue()).isEqualByComparingTo("0");
        assertThat(created.path("wallGapSource").asText()).isEqualTo("CALCULATED");
        assertThat(created.path("wallArea").path("value").decimalValue()).isEqualByComparingTo("26.43");

        // The save of room 1701: root wallGap = 2, finishGap = 3 (MANUAL), wallArea = 12.93 as on screen.
        Map<String, Object> update = updateBody();
        update.put("wallGap", 2);
        update.put("wallGapSource", "MANUAL");
        update.put("finishGap", 3);
        update.put("finishGapSource", "MANUAL");
        update.put("wallArea", 12.93);
        saveUpdate(roomId, update);

        assertRoom1701State(roomId);

        // Second save with the values read back (as the matrix re-sends them): nothing changes.
        JsonNode read = readRoom(roomId);
        Map<String, Object> again = updateBody();
        again.put("wallGap", read.path("wallGap").decimalValue());
        again.put("wallGapSource", read.path("wallGapSource").asText());
        again.put("finishGap", read.path("finishGap").decimalValue());
        again.put("finishGapSource", read.path("finishGapSource").asText());
        again.put("wallArea", read.path("wallArea").path("value").decimalValue());
        again.put("doorHeight", read.path("doorHeight").decimalValue());
        again.put("doorHeightSource", read.path("doorHeightSource").asText());
        again.put("doorWidth", read.path("doorWidth").decimalValue());
        again.put("doorWidthSource", read.path("doorWidthSource").asText());
        saveUpdate(roomId, again);

        assertRoom1701State(roomId);
    }

    /** Persisted columns (raw SQL) and the read model of room 1701 after the manual-gap save. */
    private void assertRoom1701State(long roomId) throws Exception {
        try (Connection c = connect(POSTGRES); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT wall_gap, finish_gap, wall_area, door_height, door_width, "
                     + "wall_gap_source, finish_gap_source, door_height_source, door_width_source, "
                     + "window_height_source, window_width_source, wall_area_source, perimeter "
                     + "FROM rooms WHERE id = " + roomId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal("wall_gap")).isEqualByComparingTo("2");
            assertThat(rs.getBigDecimal("finish_gap")).isEqualByComparingTo("3");
            assertThat(rs.getBigDecimal("wall_area")).isEqualByComparingTo("12.93");
            assertThat(rs.getBigDecimal("perimeter")).isEqualByComparingTo("10.22");
            assertThat(rs.getBigDecimal("door_height")).isEqualByComparingTo("2.0");
            assertThat(rs.getBigDecimal("door_width")).isEqualByComparingTo("0.58");
            assertThat(rs.getString("wall_gap_source")).isEqualTo("MANUAL");
            assertThat(rs.getString("finish_gap_source")).isEqualTo("MANUAL");
            assertThat(rs.getString("door_height_source")).isEqualTo("CALCULATED");
            assertThat(rs.getString("door_width_source")).isEqualTo("CALCULATED");
            assertThat(rs.getString("window_height_source")).isEqualTo("CALCULATED");
            assertThat(rs.getString("window_width_source")).isEqualTo("CALCULATED");
            assertThat(rs.getString("wall_area_source")).isEqualTo("CALCULATED");
        }

        JsonNode read = readRoom(roomId);
        assertThat(read.path("wallGap").decimalValue()).isEqualByComparingTo("2");
        assertThat(read.path("finishGap").decimalValue()).isEqualByComparingTo("3");
        assertThat(read.path("wallGapSource").asText()).isEqualTo("MANUAL");
        assertThat(read.path("finishGapSource").asText()).isEqualTo("MANUAL");
        assertThat(read.path("wallArea").path("value").decimalValue()).isEqualByComparingTo("12.93");
        assertThat(read.path("doorHeight").decimalValue()).isEqualByComparingTo("2.0");
        assertThat(read.path("doorWidth").decimalValue()).isEqualByComparingTo("0.58");
        assertThat(read.path("doorHeightSource").asText()).isEqualTo("CALCULATED");
        assertThat(read.path("doorWidthSource").asText()).isEqualTo("CALCULATED");
        assertThat(read.path("windowHeight").isNull()).as("no window: size is a dash").isTrue();
        assertThat(read.path("windowWidth").isNull()).isTrue();

        // Perimeter explanation: wall1 + wall2 + wall3 + wall4 = 3 + 2.11 + 3 + 2.11.
        JsonNode explanation = read.path("metricExplanations").path("perimeter");
        assertThat(explanation.isMissingNode()).as("perimeter explanation present").isFalse();
        assertThat(explanation.path("formula").asText().replace(" ", "")).isEqualTo("wall1+wall2+wall3+wall4");
        JsonNode vars = explanation.path("variables");
        assertThat(vars.path("wall1").decimalValue()).isEqualByComparingTo("3");
        assertThat(vars.path("wall2").decimalValue()).isEqualByComparingTo("2.11");
        assertThat(vars.path("wall3").decimalValue()).isEqualByComparingTo("3");
        assertThat(vars.path("wall4").decimalValue()).isEqualByComparingTo("2.11");
        // The wall area explanation uses the manual gaps.
        JsonNode wallVars = read.path("metricExplanations").path("wallArea").path("variables");
        assertThat(wallVars.path("wallGap").decimalValue()).isEqualByComparingTo("2");
        assertThat(wallVars.path("finishGap").decimalValue()).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------ changeset 266

    @Test
    @DisplayName("changeset 266: backfills CALCULATED on existing rooms and a re-run changes nothing")
    void changeset266BackfillsAndIsIdempotent() throws Exception {
        MIGRATION_DB.start();
        try {
            migrate(MIGRATION_DB, true);
            assertThat(columnCount("wall_gap_source")).as("column absent before 266").isZero();

            String run = Long.toString(System.nanoTime());
            long project = insert("projects", Map.of("name", "mig266-" + run, "status", "ACTIVE"));
            long type = insert("room_types", Map.of("code", "MIG266-" + run, "active", "true"));
            long withValues = insert("rooms", roomValues(project, type, "with-" + run, "2.00", "3.00", "2.00", "0.58"));
            long withoutValues = insert("rooms", roomValues(project, type, "without-" + run, null, null, null, null));

            migrate(MIGRATION_DB, false);
            assertThat(columnCount("wall_gap_source")).isEqualTo(1L);
            assertThat(columnCount("window_width_source")).isEqualTo(1L);
            assertThat(sources(withValues))
                    .containsExactly("CALCULATED", "CALCULATED", "CALCULATED", "CALCULATED", null, null);
            assertThat(sources(withoutValues)).containsExactly(null, null, null, null, null, null);
            assertThat(query("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '266-%' AND exectype = 'EXECUTED'"))
                    .isEqualTo(2L);

            // Re-run: the 266 bookkeeping rows are removed, one value is flagged MANUAL meanwhile.
            execute("UPDATE rooms SET wall_gap_source = 'MANUAL' WHERE id = " + withValues);
            execute("DELETE FROM databasechangelog WHERE id LIKE '266-%'");
            migrate(MIGRATION_DB, false);
            assertThat(query("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '266-%'")).isEqualTo(2L);
            assertThat(query("SELECT COUNT(*) FROM databasechangelog WHERE id LIKE '266-%' AND exectype <> 'MARK_RAN'"))
                    .isZero();
            assertThat(sources(withValues))
                    .containsExactly("MANUAL", "CALCULATED", "CALCULATED", "CALCULATED", null, null);
            assertThat(sources(withoutValues)).containsExactly(null, null, null, null, null, null);
        } finally {
            MIGRATION_DB.stop();
        }
    }

    // ------------------------------------------------------------------ HTTP helpers

    /** Rectangle 3.0 x 2.11 (perimeter 10.22), ceiling 2.7, one DOOR 0.58 x 2.0 on wall 1. */
    private Map<String, Object> updateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("roomTypeId", roomTypeId);
        body.put("label", "Przedpokój " + runId);
        body.put("ceilingHeight", 2.7);
        body.put("geometry", geometry());
        return body;
    }

    private Map<String, Object> geometry() {
        Map<String, Object> door = new LinkedHashMap<>();
        door.put("type", "DOOR");
        door.put("count", 1);
        door.put("height", 2.0);
        door.put("width", 0.58);
        List<Map<String, Object>> walls = new ArrayList<>();
        walls.add(Map.of("openings", List.of(door)));
        walls.add(Map.of());
        walls.add(Map.of());
        walls.add(Map.of());
        List<Map<String, Object>> vertices = List.of(
                Map.of("x", 0.0, "y", 0.0), Map.of("x", 3.0, "y", 0.0),
                Map.of("x", 3.0, "y", 2.11), Map.of("x", 0.0, "y", 2.11));
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("vertices", vertices);
        geometry.put("walls", walls);
        return geometry;
    }

    private long createRoom() throws Exception {
        Map<String, Object> request = Map.of("createList", List.of(updateBody()));
        String body = mockMvc.perform(post("/api/rooms/project/" + projectId + "/save?force=false")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body).path("created");
        assertThat(created.isArray() && created.size() == 1).as("one room created: %s", body).isTrue();
        return created.get(0).asLong();
    }

    private void saveUpdate(long roomId, Map<String, Object> data) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", roomId);
        item.put("data", data);
        mockMvc.perform(post("/api/rooms/project/" + projectId + "/save?force=false")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("updateItems", List.of(item)))))
                .andExpect(status().isOk());
    }

    private JsonNode readRoom(long roomId) throws Exception {
        String body = mockMvc.perform(get("/api/rooms/" + roomId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    // ------------------------------------------------------------------ database helpers

    private static Connection connect(PostgreSQLContainer<?> pg) throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static void migrateFully(PostgreSQLContainer<?> pg) throws Exception {
        try {
            runChangelog(pg, null);
        } catch (Exception first) {
            seedAdminUser(pg);
            runChangelog(pg, null);
        }
    }

    /** Applies the changelog; {@code stopPrefix != null} stops before the first changeset with that id prefix. */
    private static void runChangelog(PostgreSQLContainer<?> pg, String stopPrefix) throws Exception {
        try (Connection connection = connect(pg)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                if (stopPrefix == null) {
                    liquibase.update(new Contexts());
                    return;
                }
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int before = 0;
                while (before < unrun.size() && !unrun.get(before).getId().startsWith(stopPrefix)) {
                    before++;
                }
                if (before > 0) {
                    liquibase.update(before, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    /** {@code before266 = true}: up to (excluding) 266; otherwise the rest of the changelog. */
    private static void migrate(PostgreSQLContainer<?> pg, boolean before266) throws Exception {
        String stop = before266 ? CHANGESET_PREFIX : null;
        try {
            runChangelog(pg, stop);
        } catch (Exception first) {
            seedAdminUser(pg);
            runChangelog(pg, stop);
        }
    }

    /** A changeset that needs an existing ADMIN user fails on an empty database: seed one and retry. */
    private static void seedAdminUser(PostgreSQLContainer<?> pg) throws Exception {
        try (Connection c = connect(pg); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1")) {
                if (!rs.next()) {
                    return;
                }
                long roleId = rs.getLong(1);
                try (ResultSet exists = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM users WHERE email = 'mig-admin-266@example.com'")) {
                    exists.next();
                    if (exists.getLong(1) > 0) {
                        return;
                    }
                }
                st.execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                        + "VALUES ('mig-admin', 'mig-admin-266@example.com', " + roleId + ", true, 'ru', NOW(), 'test')");
            }
        }
    }

    private static Map<String, Object> roomValues(long project, long type, String label,
            String wallGap, String finishGap, String doorHeight, String doorWidth) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("project_id", project);
        values.put("room_type_id", type);
        values.put("label", label);
        if (wallGap != null) {
            values.put("wall_gap", new BigDecimal(wallGap));
            values.put("finish_gap", new BigDecimal(finishGap));
            values.put("door_height", new BigDecimal(doorHeight));
            values.put("door_width", new BigDecimal(doorWidth));
        }
        return values;
    }

    /** The six source columns of a room, in the order wall_gap, finish_gap, door_height, door_width, window_*. */
    private List<String> sources(long roomId) throws Exception {
        try (Connection c = connect(MIGRATION_DB); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT wall_gap_source, finish_gap_source, door_height_source, "
                     + "door_width_source, window_height_source, window_width_source FROM rooms WHERE id = " + roomId)) {
            assertThat(rs.next()).isTrue();
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                out.add(rs.getString(i));
            }
            return out;
        }
    }

    private long columnCount(String column) throws Exception {
        return query("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'rooms' "
                + "AND column_name = '" + column + "'");
    }

    private long query(String sql) throws Exception {
        try (Connection c = connect(MIGRATION_DB); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection c = connect(MIGRATION_DB); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Inserts a row; NOT NULL columns without default that are not given get a dummy; FKs are not enforced. */
    private long insert(String table, Map<String, ?> values) throws Exception {
        try (Connection c = connect(MIGRATION_DB); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            Map<String, String> literals = new LinkedHashMap<>();
            values.forEach((k, v) -> literals.put(k, v instanceof Number ? v.toString()
                    : "'" + v.toString().replace("'", "''") + "'"));
            try (ResultSet rs = st.executeQuery("SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_name = '" + table + "' AND is_nullable = 'NO' AND column_default IS NULL")) {
                while (rs.next()) {
                    String column = rs.getString(1);
                    if (!"id".equals(column)) {
                        literals.putIfAbsent(column, dummy(rs.getString(2)));
                    }
                }
            }
            literals.putIfAbsent("id", "(SELECT COALESCE(MAX(id), 0) + 1000 FROM " + table + ")");
            List<String> cols = new ArrayList<>(literals.keySet());
            String sql = "INSERT INTO " + table + " (" + String.join(", ", cols.stream().map(
                    col -> "\"" + col + "\"").toList()) + ") VALUES ("
                    + String.join(", ", cols.stream().map(literals::get).toList()) + ") RETURNING id";
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String dummy(String dataType) {
        return switch (dataType) {
            case "bigint", "integer", "smallint", "numeric" -> "1";
            case "boolean" -> "false";
            case "date" -> "CURRENT_DATE";
            case "timestamp without time zone", "timestamp with time zone" -> "NOW()";
            case "jsonb", "json" -> "'{}'";
            default -> "'x'";
        };
    }
}
