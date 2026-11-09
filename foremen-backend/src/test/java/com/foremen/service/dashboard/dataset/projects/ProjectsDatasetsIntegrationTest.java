package com.foremen.service.dashboard.dataset.projects;

import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registry startup validation and query compilation for the FOR-10 projects/team/schedule datasets.
 *
 * <p><b>Validates: Requirements 6.2, 8</b>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ProjectsDatasetsIntegrationTest {

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

    private static final Set<String> CODES = Set.of(
            "PROJECTS", "PROJECT_MEMBERS", "ROOMS", "SCHEDULE_BARS", "COMPANY_SCHEDULE_BARS");
    private static final List<String> FORBIDDEN = List.of(
            "email", "phone", "nip", "passwordHash", "latitude", "longitude", "googlePlaceId",
            "geometry", "dimensionFormulas", "tags");

    @Autowired
    private DatasetRegistry registry;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("all five datasets are registered with expected scope and resources")
    void registered() {
        for (String code : CODES) {
            assertThat(registry.get(code)).as(code).isPresent();
        }
        assertThat(registry.get("PROJECTS").orElseThrow().projectPath()).contains("id");
        assertThat(registry.get("PROJECT_MEMBERS").orElseThrow().projectPath()).contains("projectId");
        assertThat(registry.get("ROOMS").orElseThrow().projectPath()).contains("project.id");
        assertThat(registry.get("SCHEDULE_BARS").orElseThrow().requiredResources())
                .containsExactly("WORK_SCHEDULE");
        assertThat(registry.get("COMPANY_SCHEDULE_BARS").orElseThrow().requiredResources())
                .containsExactly("COMPANY_SCHEDULE");
        assertThat(registry.get("COMPANY_SCHEDULE_BARS").orElseThrow().projectPath())
                .contains("schedule.project.id");
    }

    @Test
    @DisplayName("no sensitive fields are exposed")
    void noSensitiveFields() {
        for (String code : CODES) {
            for (FieldDescriptor f : registry.get(code).orElseThrow().allFields()) {
                String lower = f.name().toLowerCase();
                for (String bad : FORBIDDEN) {
                    assertThat(lower).as(code + "." + f.name()).doesNotContain(bad.toLowerCase());
                }
            }
        }
    }

    @Test
    @DisplayName("every field and lookup compiles and executes against the database")
    void fieldsExecute() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        for (String code : CODES) {
            Dataset d = registry.get(code).orElseThrow();
            for (FieldDescriptor f : d.allFields()) {
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<?> root = q.from(d.rootEntity());
                if (f.computed() != null) {
                    q.multiselect(f.computed().build(cb, q, root));
                } else {
                    Path<?> p = root;
                    for (String seg : f.jpaPath().split("\\.")) {
                        p = p.get(seg);
                    }
                    q.multiselect(p);
                }
                assertThat(em.createQuery(q).setMaxResults(1).getResultList())
                        .as(code + "." + f.name()).isNotNull();
            }
        }
    }
}
