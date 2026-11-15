package com.foremen.dao.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.ShipmentLineDao;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-11-04 task 3.1 - validates the shipment JPA mapping against the real Liquibase schema
 * (changesets 241 and 243). The full changelog is applied to a Testcontainers PostgreSQL, then the context
 * boots with {@code ddl-auto=validate} restricted to the shipment tables and {@code purchase_positions}.
 *
 * <p>Repeatability: every run gets a fresh container; the test only reads.
 *
 * <p>Validates Requirements 2.1, 2.2, 2.3.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider="
                + "com.foremen.dao.integration.ShipmentMappingSchemaIntegrationTest$ShipmentTablesSchemaFilterProvider",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-11-04, task 3.1: entity mapping vs schema 241, 243")
class ShipmentMappingSchemaIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final Set<String> VALIDATED_TABLES =
            Set.of("shipments", "shipment_lines", "shipment_events", "purchase_positions");

    public static class ShipmentTablesSchemaFilterProvider implements SchemaFilterProvider {
        private static final SchemaFilter FILTER = new SchemaFilter() {
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
            return FILTER;
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

    @Autowired
    private ShipmentDao shipmentDao;
    @Autowired
    private ShipmentLineDao lineDao;
    @Autowired
    private ShipmentEventDao eventDao;

    @Test
    @DisplayName("context boots under ddl-auto=validate and the DAOs read the migrated tables")
    void mappingMatchesSchema() {
        assertThat(shipmentDao.count()).isZero();
        assertThat(lineDao.count()).isZero();
        assertThat(eventDao.count()).isZero();
    }
}
