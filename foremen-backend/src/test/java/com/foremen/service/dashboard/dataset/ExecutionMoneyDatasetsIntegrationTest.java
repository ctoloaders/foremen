package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Column;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.dashboard.query.DashboardQueryEngine;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.EntityManager;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 2.4.4: the execution / procurement / money datasets resolve against the real JPA
 * metamodel, expose no sensitive field, declare the expected scope, and every field is queryable
 * (Criteria expressions compile and run on PostgreSQL) for ADMIN, a project-scoped MANAGER and a WORKER.
 *
 * <p><b>Validates: Requirements 6.2, 8</b>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class ExecutionMoneyDatasetsIntegrationTest {

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

    private static final Set<String> DENY = Set.of("passwordHash", "iban", "accountNumber", "externalId",
            "externalConnectionId", "externalConsentId", "email", "phone", "nip", "snapshotBefore",
            "snapshotAfter", "body", "deepLink", "originalText", "documentUri", "contentHash",
            "rawObjectKey", "processedObjectKey", "storageUri", "note", "externalRef", "supplierNip",
            "invoiceNumber", "rejectionReason", "counterpartyIban", "counterpartyKey", "holderName",
            "lastErrorMessage", "bankLogoUrl");

    private static final Set<String> CODES = Set.of("WORK_REPORTS", "PURCHASES", "PURCHASE_LINES",
            "COMPANY_PURCHASES", "COMPANY_PURCHASE_LINES", "CLIENT_MESSAGES", "PAYMENTS",
            "PAYMENT_SCHEDULES", "PAYMENT_TRANCHES", "PAYROLL", "WORKER_PAYOUTS", "BANK_CONNECTIONS",
            "BANK_ACCOUNTS", "BANK_LEDGER");

    @Autowired
    private EntityManager em;

    @Autowired
    private List<Dataset> allBeans;

    private List<Dataset> mine() {
        return allBeans.stream().filter(d -> CODES.contains(d.code())).toList();
    }

    @Test
    @DisplayName("all 14 datasets are beans and pass registry validation against the metamodel")
    void registeredAndValid() {
        assertThat(mine()).extracting(Dataset::code).containsExactlyInAnyOrderElementsOf(CODES);
        // throws IllegalStateException when any project/owner/field path does not resolve
        DatasetRegistry registry = new DatasetRegistry(mine(), em.getEntityManagerFactory().getMetamodel());
        assertThat(registry.all()).hasSize(CODES.size());
    }

    @Test
    @DisplayName("no dataset exposes a sensitive field name at any depth")
    void noSensitiveFields() {
        for (Dataset d : mine()) {
            for (FieldDescriptor f : d.allFields()) {
                for (String segment : f.name().split("\\.")) {
                    assertThat(DENY).as("%s.%s", d.code(), f.name()).doesNotContain(segment);
                }
            }
        }
    }

    @Test
    @DisplayName("required resources, scope and owner restriction match the design")
    void scopeAndResources() {
        assertThat(by("BANK_ACCOUNTS").requiredResources()).containsExactly("BANK_CONNECTIONS");
        assertThat(by("BANK_LEDGER").requiredResources()).containsExactly("BANK_LEDGER");
        assertThat(by("PURCHASE_LINES").requiredResources()).containsExactly("PURCHASES");
        assertThat(by("COMPANY_PURCHASE_LINES").requiredResources()).containsExactly("COMPANY_PURCHASES");
        assertThat(by("PAYMENT_TRANCHES").requiredResources()).containsExactly("PAYMENTS");
        assertThat(by("WORKER_PAYOUTS").requiredResources()).containsExactly("PAYROLL");
        assertThat(by("WORK_REPORTS").projectPath()).contains("project.id");
        assertThat(by("PAYMENTS").projectPath()).contains("schedule.project.id");
        for (String company : List.of("COMPANY_PURCHASES", "COMPANY_PURCHASE_LINES", "PAYROLL",
                "WORKER_PAYOUTS", "BANK_CONNECTIONS", "BANK_ACCOUNTS", "BANK_LEDGER")) {
            assertThat(by(company).projectPath()).as(company).isEmpty();
        }
        for (String owned : List.of("WORK_REPORTS", "PAYROLL", "WORKER_PAYOUTS")) {
            assertThat(by(owned).ownerPath()).as(owned).contains("worker.id");
            assertThat(by(owned).ownerRestrictedRoles()).as(owned).containsExactly("WORKER");
        }
    }

    @Test
    @DisplayName("every field of every dataset is queryable for ADMIN, MANAGER and WORKER")
    void everyFieldRuns() {
        DashboardQueryEngine engine = new DashboardQueryEngine(em, ZoneId.of("UTC"), "PLN");
        LocalDate today = LocalDate.now();
        QueryContext admin = new QueryContext(1L, "ADMIN", Set.of(), true, today.minusDays(30), today, null, "pl");
        QueryContext manager = new QueryContext(1L, "MANAGER", Set.of(1L, 2L), false, null, null, null, "pl");
        QueryContext worker = new QueryContext(1L, "WORKER", Set.of(1L), false, null, null, null, "pl");
        for (Dataset d : mine()) {
            List<Column> columns = d.allFields().stream().map(f -> new Column(f.name(), null, null))
                    .collect(Collectors.toList());
            WidgetConfig cfg = new WidgetConfig(1, d.code(), Visualization.TABLE, null, columns,
                    null, null, List.of(), null, null, null, 5, null, null);
            for (QueryContext ctx : List.of(admin, manager, worker)) {
                assertThat(engine.execute(d, cfg, ctx)).as("%s as %s", d.code(), ctx.roleCode()).isNotNull();
            }
        }
    }

    private Dataset by(String code) {
        return mine().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();
    }
}
