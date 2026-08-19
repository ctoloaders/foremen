package com.foremen.dao.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers configuration for integration tests.
 * Uses {@link ServiceConnection} to automatically configure the datasource
 * from the container, preventing port conflicts when Spring caches application contexts.
 */
@Configuration
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    static PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("foremen_test")
                .withUsername("test")
                .withPassword("test");
    }
}
