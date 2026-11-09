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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 2.4.5: no registered dataset exposes a deny-listed field name at any join depth, and the
 * system datasets match the design (owner rules, scope) and run on PostgreSQL.
 *
 * <p><b>Validates: Requirements 6.2b, 6.2c, 8.3, 8.5</b>
 */
class SensitiveFieldGuardTest extends AbstractDatasetIntegrationTest {

    /** Design deny-list plus the sensitive names found while building the earlier datasets. */
    static final Set<String> DENY = Set.of("passwordHash", "iban", "accountNumber", "externalId",
            "externalConnectionId", "externalConsentId", "email", "phone", "nip", "snapshotBefore",
            "snapshotAfter", "body", "deepLink", "originalText", "documentUri", "contentHash",
            "rawObjectKey", "processedObjectKey", "storageUri", "supplierNip", "counterpartyIban",
            "counterpartyKey", "holderName", "lastErrorMessage", "bankLogoUrl", "contactPerson");

    @Autowired
    private DatasetRegistry registry;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("no dataset field name or JPA path segment is on the deny-list")
    void noSensitiveFieldAtAnyDepth() {
        assertThat(registry.all()).hasSizeGreaterThanOrEqualTo(38);
        for (Dataset d : registry.all()) {
            for (FieldDescriptor f : d.allFields()) {
                for (String segment : f.name().split("\\.")) {
                    assertThat(DENY).as("%s field %s", d.code(), f.name()).doesNotContain(segment);
                }
                if (f.jpaPath() != null) {
                    for (String segment : f.jpaPath().split("\\.")) {
                        assertThat(DENY).as("%s path %s", d.code(), f.jpaPath()).doesNotContain(segment);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("system datasets: resources, company scope and owner rules")
    void systemDatasetsShape() {
        assertThat(by("USERS").requiredResources()).containsExactly("USERS");
        assertThat(by("ROLES").requiredResources()).containsExactly("ROLES");
        assertThat(by("AUDIT_LOG").requiredResources()).containsExactly("AUDIT");
        assertThat(by("NOTIFICATIONS").requiredResources()).containsExactly("NOTIFICATIONS");
        for (String code : List.of("USERS", "ROLES", "AUDIT_LOG", "NOTIFICATIONS")) {
            assertThat(by(code).projectPath()).as(code).isEmpty();
        }
        assertThat(by("NOTIFICATIONS").alwaysOwn()).isTrue();
        assertThat(by("NOTIFICATIONS").ownerPath()).contains("recipient.id");
        assertThat(by("USERS").alwaysOwn()).isFalse();
        assertThat(names("USERS")).containsExactlyInAnyOrder("id", "role.code", "status", "active",
                "locale", "workerType.code", "workerType.nameRU", "workerType.namePL", "workerKind", "createdDate");
        assertThat(names("AUDIT_LOG")).containsExactlyInAnyOrder("id", "entityClass", "entityId",
                "operation", "performedBy", "performedAt");
        assertThat(names("NOTIFICATIONS")).containsExactlyInAnyOrder("id", "type", "read", "createdDate");
    }

    @Test
    @DisplayName("every system dataset field is queryable for ADMIN and a non-admin")
    void systemFieldsRun() {
        DashboardQueryEngine engine = new DashboardQueryEngine(em, ZoneId.of("UTC"), "PLN");
        LocalDate today = LocalDate.now();
        QueryContext admin = new QueryContext(1L, "ADMIN", Set.of(), true, today.minusDays(30), today, null, "pl");
        QueryContext manager = new QueryContext(1L, "MANAGER", Set.of(1L), false, null, null, null, "pl");
        for (String code : List.of("USERS", "ROLES", "AUDIT_LOG", "NOTIFICATIONS")) {
            Dataset d = by(code);
            List<Column> columns = d.allFields().stream().map(f -> new Column(f.name(), null, null))
                    .collect(Collectors.toList());
            WidgetConfig cfg = new WidgetConfig(1, code, Visualization.TABLE, null, columns,
                    null, null, List.of(), null, null, null, 5, null, null);
            for (QueryContext ctx : List.of(admin, manager)) {
                assertThat(engine.execute(d, cfg, ctx)).as("%s as %s", code, ctx.roleCode()).isNotNull();
            }
        }
    }

    private Set<String> names(String code) {
        return by(code).allFields().stream().map(FieldDescriptor::name).collect(Collectors.toSet());
    }

    private Dataset by(String code) {
        return registry.get(code).orElseThrow();
    }
}
