package com.foremen.qa.fixtures;

import com.foremen.qa.support.TestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * JDBC connection to the isolated test database used for direct test-user provisioning
 * (Requirement 12.2, 12.10).
 *
 * <p>Holds a single process-wide {@link HikariDataSource} built from {@link TestConfig}
 * ({@code QA_DB_URL} / {@code QA_DB_USER} / {@code QA_DB_PASSWORD}, defaulting to the
 * {@code docker-compose.yml} values {@code jdbc:postgresql://localhost:5432/foremen} and
 * {@code foremen}/{@code foremen}). Opening a pooled {@code DataSource} once per run and reusing it
 * keeps provisioning cheap while a small pool is enough for the serial smoke suite.
 *
 * <p>The pool is created lazily on first {@link #dataSource()} call and closed by {@link #shutdown()}
 * at the end of the run. All access is direct JDBC against this pool — no application code is added
 * (Requirement 12.8).
 */
public final class Db {

    /** Small pool: the smoke suite runs scenarios serially, so a handful of connections suffices. */
    private static final int MAX_POOL_SIZE = 4;

    private static volatile HikariDataSource dataSource;

    private Db() {
    }

    /**
     * The shared {@link DataSource}, created lazily from {@link TestConfig} on first use. Thread-safe
     * via double-checked locking so parallel scenarios (if ever enabled) share one pool.
     */
    public static DataSource dataSource() {
        HikariDataSource local = dataSource;
        if (local == null) {
            synchronized (Db.class) {
                local = dataSource;
                if (local == null) {
                    local = build();
                    dataSource = local;
                }
            }
        }
        return local;
    }

    private static HikariDataSource build() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("qa-test-user-pool");
        config.setJdbcUrl(TestConfig.dbUrl());
        config.setUsername(TestConfig.dbUser());
        config.setPassword(TestConfig.dbPassword());
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setDriverClassName("org.postgresql.Driver");
        // Fail fast with a clear message if the DB is unreachable rather than hanging a scenario.
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(10_000);
        try {
            return new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot connect to the test database at '" + TestConfig.dbUrl()
                            + "' as user '" + TestConfig.dbUser() + "'. Is `docker compose up` running? "
                            + "Override with " + TestConfig.KEY_DB_URL + " / " + TestConfig.KEY_DB_USER
                            + " / " + TestConfig.KEY_DB_PASSWORD + ".", e);
        }
    }

    /** Close the pool at the end of the run. Safe to call when no pool was opened. */
    public static synchronized void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
