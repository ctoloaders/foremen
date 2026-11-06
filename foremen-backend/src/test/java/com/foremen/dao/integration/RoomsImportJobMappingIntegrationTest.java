package com.foremen.dao.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-05-02 task 1.1 — validates the {@code rooms_import_jobs} mapping against changeset
 * {@code 213} ({@code ddl-auto=validate} on that table), the one-job-per-project UNIQUE index, the
 * jsonb round trip and the DAO lookups.
 *
 * <p>Repeatability: fresh container per run; project names / emails carry a per-run id.
 *
 * <p>Validates: Requirements 2.1, 2.3, 2.4, 16.1, 19.1
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider="
                + "com.foremen.dao.integration.RoomsImportJobMappingIntegrationTest$JobTableSchemaFilterProvider",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-05-02-rooms-autoimport, task 1.1: rooms_import_jobs mapping vs schema 213")
class RoomsImportJobMappingIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final Set<String> VALIDATED_TABLES = Set.of("rooms_import_jobs");

    /** Restricts Hibernate schema validation to {@code rooms_import_jobs}. */
    public static class JobTableSchemaFilterProvider implements SchemaFilterProvider {
        private static final SchemaFilter ONLY_JOBS = new SchemaFilter() {
            @Override
            public boolean includeNamespace(Namespace namespace) {
                return true;
            }

            @Override
            public boolean includeTable(Table table) {
                return VALIDATED_TABLES.contains(table.getName().replace("\"", "").toLowerCase(Locale.ROOT));
            }

            @Override
            public boolean includeSequence(Sequence sequence) {
                return false;
            }
        };

        @Override
        public SchemaFilter getCreateFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getDropFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getTruncatorFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getMigrateFilter() {
            return SchemaFilter.ALL;
        }

        @Override
        public SchemaFilter getValidateFilter() {
            return ONLY_JOBS;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyMigrationsOnce();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static synchronized void applyMigrationsOnce() throws Exception {
        if (migrated) {
            return;
        }
        try {
            runLiquibase();
        } catch (Exception first) {
            // A changeset may need an ADMIN user the app seeds at startup: seed one and resume.
            seedAdminUser();
            runLiquibase();
        }
        migrated = true;
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void runLiquibase() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUser() throws Exception {
        try (Connection c = newConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1")) {
                if (!rs.next()) {
                    return;
                }
                long roleId = rs.getLong(1);
                st.execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                        + "VALUES ('mig-admin', 'mig-admin@example.com', " + roleId + ", true, 'ru', NOW(), 'test')");
            }
        }
    }

    @Autowired private RoomsImportJobDao jobDao;
    @Autowired private ProjectDao projectDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private ProjectEntity newProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("Import project " + runId + "-" + UUID.randomUUID());
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private UserEntity newUser() {
        UserEntity user = new UserEntity();
        user.setName("Import user " + runId);
        user.setEmail("import+" + UUID.randomUUID() + "@example.com");
        user.setRole(roleDao.findByCode("ADMIN").orElseThrow());
        user.setActive(true);
        return userDao.save(user);
    }

    private RoomsImportJobEntity newJob(ProjectEntity project, UserEntity user) {
        RoomsImportJobEntity job = new RoomsImportJobEntity();
        job.setProject(project);
        job.setCreator(user);
        job.setStatus(RoomsImportStatus.QUEUED);
        job.setPromptText("three rooms");
        job.setInputs(List.of(Map.of("fileName", "plan.pdf", "contentType", "application/pdf",
                "sizeBytes", 123, "objectKey", "rooms-import/1/in/0", "kind", "PDF")));
        job.setStageLog(List.of(Map.of("stage", "QUEUED", "at", "2026-01-01T00:00:00")));
        job.setStartedAt(LocalDateTime.now());
        job.setExpiresAt(LocalDateTime.now().plusDays(1));
        return job;
    }

    @Test
    @DisplayName("persists a job with jsonb inputs/result/stage_log and finds it by project (plain and FOR UPDATE)")
    void roundTripAndFinders() {
        Long projectId = tx().execute(s -> {
            ProjectEntity project = newProject();
            RoomsImportJobEntity job = newJob(project, newUser());
            job.setResult(Map.of("rooms", List.of(Map.of("tempId", "r1"))));
            jobDao.save(job);
            return project.getId();
        });

        tx().executeWithoutResult(s -> {
            RoomsImportJobEntity plain = jobDao.findByProjectId(projectId).orElseThrow();
            assertThat(plain.getStatus()).isEqualTo(RoomsImportStatus.QUEUED);
            assertThat(plain.getInputs()).hasSize(1);
            assertThat(plain.getInputs().get(0)).containsEntry("fileName", "plan.pdf");
            assertThat(plain.getStageLog()).hasSize(1);
            assertThat(plain.getResult()).containsKey("rooms");
            assertThat(plain.getAppliedAt()).isNull();

            RoomsImportJobEntity locked = jobDao.findByProjectIdForUpdate(projectId).orElseThrow();
            assertThat(locked.getId()).isEqualTo(plain.getId());
        });

        assertThat(jobDao.findByProjectId(-1L)).isEmpty();
        assertThat(RoomsImportStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(RoomsImportStatus.MEASURING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("UNIQUE index on project_id rejects a second job row for the same project")
    void uniqueJobPerProject() {
        Object[] ids = tx().execute(s -> new Object[] {newProject(), newUser()});
        ProjectEntity project = (ProjectEntity) ids[0];
        UserEntity user = (UserEntity) ids[1];

        tx().executeWithoutResult(s -> jobDao.save(newJob(project, user)));

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> jobDao.save(newJob(project, user))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
