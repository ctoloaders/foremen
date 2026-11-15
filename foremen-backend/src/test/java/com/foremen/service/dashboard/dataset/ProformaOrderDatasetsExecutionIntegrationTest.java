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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-03 task 8.1: every field of {@code CLIENT_PROFORMAS} and {@code EXTERNAL_ORDERS} (including the
 * computed {@code overdue} and {@code payableGross}) is queryable on PostgreSQL for ADMIN, the
 * company-wide FINANCIER / BUYER, and a project-scoped MANAGER (Requirements 20.3, 27.2).
 */
class ProformaOrderDatasetsExecutionIntegrationTest extends AbstractDatasetIntegrationTest {

    @Autowired
    private DatasetRegistry registry;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("both datasets are registered and every field runs for each role")
    void allFieldsRun() {
        DashboardQueryEngine engine = new DashboardQueryEngine(em, ZoneId.of("UTC"), "PLN");
        LocalDate today = LocalDate.now();
        List<QueryContext> contexts = List.of(
                new QueryContext(1L, "ADMIN", Set.of(), true, today.minusDays(30), today, null, "pl"),
                new QueryContext(1L, "FINANCIER", Set.of(), false, null, null, null, "pl"),
                new QueryContext(1L, "BUYER", Set.of(), false, null, null, null, "ru"),
                new QueryContext(1L, "MANAGER", Set.of(1L), false, null, null, null, "ru"));
        for (String code : List.of("CLIENT_PROFORMAS", "EXTERNAL_ORDERS")) {
            Dataset d = registry.get(code).orElseThrow();
            List<Column> columns = d.allFields().stream().map(f -> new Column(f.name(), null, null)).toList();
            WidgetConfig cfg = new WidgetConfig(1, code, Visualization.TABLE, null, columns,
                    null, null, List.of(), null, null, null, 5, null, null);
            for (QueryContext ctx : contexts) {
                assertThat(engine.execute(d, cfg, ctx)).as("%s as %s", code, ctx.roleCode()).isNotNull();
            }
        }
    }
}
