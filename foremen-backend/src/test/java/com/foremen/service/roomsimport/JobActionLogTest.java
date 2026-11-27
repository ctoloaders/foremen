package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-04 task 9.5 (Requirements 26.1, 26.3): bound, order and concurrent appends of {@link JobActionLog}
 * against a real PostgreSQL (the append is one JSONB SQL statement).
 *
 * <p>Repeatability: every test inserts its own job row; no shared state between tests.
 */
class JobActionLogTest {

    static PostgreSQLContainer<?> postgres;
    static JdbcTemplate jdbc;
    static JobActionLog log;
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE rooms_import_jobs (id BIGSERIAL PRIMARY KEY, action_log JSONB NULL)");
        log = new JobActionLog(jdbc, MAPPER);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static long newJob() {
        return jdbc.queryForObject("INSERT INTO rooms_import_jobs DEFAULT VALUES RETURNING id", Long.class);
    }

    private static JsonNode logOf(long id) throws Exception {
        return MAPPER.readTree(jdbc.queryForObject("SELECT action_log::text FROM rooms_import_jobs WHERE id = ?",
                String.class, id));
    }

    @Test
    void append_prependsNewestFirst_withEntryShape() throws Exception {
        long id = newJob();
        log.append(id, RoomsImportStatus.SAVING_ROOMS, JobActionLog.ROOM_SAVED, Map.of("room", "Kitchen"));
        log.append(id, RoomsImportStatus.PLANNING_WORKS, JobActionLog.PLAN_READY, Map.of("works", 12));

        JsonNode arr = logOf(id);
        assertThat(arr).hasSize(2);
        assertThat(arr.get(0).get("code").asText()).isEqualTo("PLAN_READY");
        assertThat(arr.get(0).get("stage").asText()).isEqualTo("PLANNING_WORKS");
        assertThat(arr.get(0).get("params").get("works").asInt()).isEqualTo(12);
        assertThat(arr.get(0).hasNonNull("at")).isTrue();
        assertThat(arr.get(1).get("code").asText()).isEqualTo("ROOM_SAVED");
    }

    @Test
    void append_boundsTo200_keepingMostRecent() throws Exception {
        long id = newJob();
        for (int i = 0; i < 250; i++) {
            log.append(id, RoomsImportStatus.PLANNING_WORKS, JobActionLog.WORK_ASSIGNED, Map.of("n", i));
        }
        JsonNode arr = logOf(id);
        assertThat(arr).hasSize(JobActionLog.MAX_ENTRIES);
        assertThat(arr.get(0).get("params").get("n").asInt()).isEqualTo(249);
        assertThat(arr.get(199).get("params").get("n").asInt()).isEqualTo(50);
    }

    @Test
    void concurrentAppends_loseNothing() throws Exception {
        long id = newJob();
        int threads = 8;
        int each = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int tt = t;
            pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    log.append(id, RoomsImportStatus.PLANNING_WORKS, JobActionLog.WORK_ASSIGNED,
                            Map.of("n", tt * 1000 + i));
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        JsonNode arr = logOf(id);
        assertThat(arr).hasSize(threads * each);
        List<Integer> seen = new ArrayList<>();
        arr.forEach(n -> seen.add(n.get("params").get("n").asInt()));
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void append_unknownJob_updatesNothing() {
        assertThat(log.append(-1L, RoomsImportStatus.PLANNING_WORKS, JobActionLog.ROOM_SAVED, null)).isZero();
    }
}
