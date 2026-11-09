package com.foremen.service.dashboard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.scoping.ScopedFixtureDao;
import com.foremen.scoping.ScopedFixtureEntity;
import com.foremen.scoping.ScopedFixtureProject;
import com.foremen.scoping.ScopedFixtureProjectDao;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Agg;
import com.foremen.service.dashboard.config.WidgetConfig.Bucket;
import com.foremen.service.dashboard.config.WidgetConfig.Column;
import com.foremen.service.dashboard.config.WidgetConfig.Dimension;
import com.foremen.service.dashboard.config.WidgetConfig.Filter;
import com.foremen.service.dashboard.config.WidgetConfig.Measure;
import com.foremen.service.dashboard.config.WidgetConfig.Op;
import com.foremen.service.dashboard.config.WidgetConfig.Presentation;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link DashboardQueryEngine} and {@link DatasetRegistry} over the scoping test
 * fixture entities. Every row carries a unique run id, so the test is repeatable.
 *
 * <p><b>Validates: Requirements 5.5, 6.1, 6.4, 6.5, 6.6, 7.2, 8.3, 8.4</b>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class DashboardQueryEngineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EntityManager em;
    @Autowired
    private ScopedFixtureDao fixtureDao;
    @Autowired
    private ScopedFixtureProjectDao projectDao;
    @Autowired
    private DatasetRegistry beanRegistry;

    private DashboardQueryEngine engine;
    private DatasetRegistry registry;
    private Dataset dataset;
    private String runId;
    private ScopedFixtureProject projectA;
    private ScopedFixtureProject projectB;
    private List<String> labels;

    /** Project-scoped dataset over {@link ScopedFixtureEntity}, owner = plain {@code projectId} column. */
    private static class FixtureDataset implements Dataset {
        private final String code;
        private final boolean restricted;

        FixtureDataset(String code, boolean restricted) {
            this.code = code;
            this.restricted = restricted;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public Set<String> requiredResources() {
            return Set.of("FIXTURE");
        }

        @Override
        public List<FieldDescriptor> fields() {
            return List.of(
                    FieldDescriptor.of("id", FieldType.ID),
                    FieldDescriptor.of("label", FieldType.STRING),
                    FieldDescriptor.of("project.id", FieldType.ID),
                    FieldDescriptor.of("project.title", FieldType.STRING),
                    FieldDescriptor.of("createdDate", FieldType.DATETIME));
        }

        @Override
        public Class<?> rootEntity() {
            return ScopedFixtureEntity.class;
        }

        @Override
        public Optional<String> projectPath() {
            return Optional.of("project.id");
        }

        @Override
        public Optional<String> ownerPath() {
            return restricted ? Optional.of("project.id") : Optional.empty();
        }

        @Override
        public Set<String> ownerRestrictedRoles() {
            return restricted ? Set.of("WORKER") : Set.of();
        }

        @Override
        public Optional<String> defaultDateField() {
            return Optional.of("createdDate");
        }
    }

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        engine = new DashboardQueryEngine(em, ZoneId.of("UTC"), "PLN");
        dataset = new FixtureDataset("FIXTURE_ROWS", false);
        registry = new DatasetRegistry(List.of(dataset), em.getEntityManagerFactory().getMetamodel());
        projectA = project("A-" + runId);
        projectB = project("B-" + runId);
        labels = List.of(
                row("a1-" + runId, projectA), row("a2-" + runId, projectA), row("b1-" + runId, projectB));
        em.flush();
    }

    private ScopedFixtureProject project(String title) {
        ScopedFixtureProject p = new ScopedFixtureProject();
        p.setTitle(title);
        return projectDao.save(p);
    }

    private String row(String label, ScopedFixtureProject project) {
        ScopedFixtureEntity e = new ScopedFixtureEntity();
        e.setLabel(label);
        e.setProject(project);
        fixtureDao.save(e);
        return label;
    }

    private Filter onlyThisRun() {
        return new Filter("label", Op.IN, JSON.valueToTree(labels));
    }

    private static WidgetConfig table(List<Filter> filters, Integer limit) {
        return new WidgetConfig(1, "FIXTURE_ROWS", Visualization.TABLE, null,
                List.of(new Column("label", null, null), new Column("project.title", null, null)),
                null, null, filters, null, null, null, limit, null, null);
    }

    private QueryContext ctx(boolean admin, Set<Long> allowed, String role) {
        return new QueryContext(42L, role, allowed, admin, null, null, null, "pl");
    }

    @Test
    @DisplayName("non-admin sees only rows of allowed projects")
    void nonAdminScoped() {
        WidgetResult r = engine.execute(dataset, table(List.of(onlyThisRun()), null),
                ctx(false, Set.of(projectA.getId()), "MANAGER"));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows()).allSatisfy(row -> assertThat(row.get("project.title")).isEqualTo(projectA.getTitle()));
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("non-admin with no projects sees nothing; admin sees everything")
    void emptyAllowedAndAdmin() {
        assertThat(engine.execute(dataset, table(List.of(onlyThisRun()), null),
                ctx(false, Set.of(), "MANAGER")).rows()).isEmpty();
        assertThat(engine.execute(dataset, table(List.of(onlyThisRun()), null),
                ctx(true, Set.of(), "ADMIN")).rows()).hasSize(3);
    }

    @Test
    @DisplayName("bound project narrows the result")
    void boundProject() {
        QueryContext c = new QueryContext(42L, "ADMIN", Set.of(), true, null, null, projectB.getId(), "ru");
        assertThat(engine.execute(dataset, table(List.of(onlyThisRun()), null), c).rows()).hasSize(1);
    }

    @Test
    @DisplayName("limit truncates and flags the result")
    void truncation() {
        WidgetResult r = engine.execute(dataset, table(List.of(onlyThisRun()), 2), ctx(true, Set.of(), "ADMIN"));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    @DisplayName("grouped count by project, sorted descending")
    void groupedCount() {
        WidgetConfig cfg = new WidgetConfig(1, "FIXTURE_ROWS", Visualization.BAR, null, null,
                List.of(new Dimension("project.title", null, null)),
                List.of(new Measure("id", Agg.COUNT, "cnt", null, null)),
                List.of(onlyThisRun()), null, null,
                List.of(new WidgetConfig.Sort("cnt", WidgetConfig.Dir.DESC)), null, null, null);
        WidgetResult r = engine.execute(dataset, cfg, ctx(true, Set.of(), "ADMIN"));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).get("project.title")).isEqualTo(projectA.getTitle());
        assertThat(r.rows().get(0).get("cnt")).isEqualTo(2L);
        assertThat(r.rows().get(1).get("cnt")).isEqualTo(1L);
    }

    @Test
    @DisplayName("date bucket groups by month and date range filters rows")
    void dateBucketAndRange() {
        WidgetConfig cfg = new WidgetConfig(1, "FIXTURE_ROWS", Visualization.LINE, null, null,
                List.of(new Dimension("createdDate", Bucket.MONTH, null)),
                List.of(new Measure("id", Agg.COUNT, "cnt", null, null)),
                List.of(onlyThisRun()), null, null, null, null, null, null);
        LocalDate today = LocalDate.now();
        QueryContext inRange = new QueryContext(42L, "ADMIN", Set.of(), true, today.minusDays(1),
                today.plusDays(1), null, "ru");
        WidgetResult r = engine.execute(dataset, cfg, inRange);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).get("createdDate")).isEqualTo(today.withDayOfMonth(1).toString());
        assertThat(r.rows().get(0).get("cnt")).isEqualTo(3L);

        QueryContext outOfRange = new QueryContext(42L, "ADMIN", Set.of(), true, today.minusDays(40),
                today.minusDays(30), null, "ru");
        assertThat(engine.execute(dataset, cfg, outOfRange).rows()).isEmpty();
    }

    @Test
    @DisplayName("KPI compares with the previous equal-length period")
    void kpiPrevious() {
        WidgetConfig cfg = new WidgetConfig(1, "FIXTURE_ROWS", Visualization.KPI, null, null, null,
                List.of(new Measure("id", Agg.COUNT, "cnt", null, null)),
                List.of(onlyThisRun()), null, null, null, null, null,
                new Presentation(null, null, null, null, null, true));
        LocalDate today = LocalDate.now();
        QueryContext c = new QueryContext(42L, "ADMIN", Set.of(), true, today, today, null, "ru");
        WidgetResult r = engine.execute(dataset, cfg, c);
        assertThat(r.rows().get(0).get("cnt")).isEqualTo(3L);
        assertThat(r.previous()).containsEntry("cnt", 0L);
    }

    @Test
    @DisplayName("owner-restricted role only sees own rows")
    void ownerRestricted() {
        Dataset restricted = new FixtureDataset("FIXTURE_OWN", true);
        QueryContext worker = new QueryContext(projectA.getId(), "WORKER",
                Set.of(projectA.getId(), projectB.getId()), false, null, null, null, "ru");
        assertThat(engine.execute(restricted, table(List.of(onlyThisRun()), null), worker).rows()).hasSize(2);
        QueryContext manager = new QueryContext(projectA.getId(), "MANAGER",
                Set.of(projectA.getId(), projectB.getId()), false, null, null, null, "ru");
        assertThat(engine.execute(restricted, table(List.of(onlyThisRun()), null), manager).rows()).hasSize(3);
    }

    @Test
    @DisplayName("registry exposes the validator schema and catalog")
    void registrySchemaAndCatalog() {
        assertThat(registry.find("FIXTURE_ROWS")).isPresent();
        assertThat(registry.find("FIXTURE_ROWS").get().projectScoped()).isTrue();
        assertThat(registry.find("NOPE")).isEmpty();
        assertThat(registry.catalog()).singleElement()
                .satisfies(c -> assertThat(c.fields()).extracting("name").contains("label", "project.title"));
        assertThat(beanRegistry).isNotNull();
    }

    @Test
    @DisplayName("registry fails startup when the project path does not resolve")
    void registryRejectsBadProjectPath() {
        Dataset bad = new FixtureDataset("BAD", false) {
            @Override
            public Optional<String> projectPath() {
                return Optional.of("nonexistent.id");
            }
        };
        assertThatThrownBy(() -> new DatasetRegistry(List.of(bad), em.getEntityManagerFactory().getMetamodel()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BAD")
                .hasMessageContaining("projectPath");
    }
}
