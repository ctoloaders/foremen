package com.foremen.qa.support;

/**
 * Centralized configuration for the QA smoke suite (Requirements 1.3, 1.6, 2.1).
 *
 * <p>Every value is resolved in a fixed precedence order:
 * <ol>
 *   <li>JVM system property (e.g. {@code -DQA_FRONTEND_URL=...}),</li>
 *   <li>process environment variable (e.g. {@code QA_FRONTEND_URL=...}),</li>
 *   <li>a built-in default that matches {@code docker-compose.yml}.</li>
 * </ol>
 *
 * <p>The class is a stateless holder: values are read once at first access and cached, so
 * configuration is stable for the duration of a run. Keys mirror the design's configuration table.
 */
public final class TestConfig {

    // ---- Configuration keys (system property / env var names) ----
    public static final String KEY_FRONTEND_URL = "QA_FRONTEND_URL";
    public static final String KEY_API_URL = "QA_API_URL";
    public static final String KEY_ADMIN_EMAIL = "FOREMEN_ADMIN_EMAIL";
    public static final String KEY_ADMIN_PASSWORD = "FOREMEN_ADMIN_PASSWORD";
    public static final String KEY_HEADED = "QA_HEADED";
    public static final String KEY_BROWSER = "QA_BROWSER";
    public static final String KEY_TIMEOUT_MS = "QA_TIMEOUT_MS";
    public static final String KEY_READINESS_TIMEOUT_MS = "QA_READINESS_TIMEOUT_MS";
    public static final String KEY_TAGS = "QA_TAGS";
    public static final String KEY_TRACE = "QA_TRACE";
    public static final String KEY_DB_URL = "QA_DB_URL";
    public static final String KEY_DB_USER = "QA_DB_USER";
    public static final String KEY_DB_PASSWORD = "QA_DB_PASSWORD";
    public static final String KEY_CREATE_MISSING_ROLE = "QA_CREATE_MISSING_ROLE";
    public static final String KEY_REPORT_DIR = "QA_REPORT_DIR";
    public static final String KEY_SHOTS = "QA_SHOTS";
    public static final String KEY_FULLPAGE_SHOTS = "QA_FULLPAGE_SHOTS";
    public static final String KEY_REPORT_KEEP_HISTORY = "QA_REPORT_KEEP_HISTORY";

    // ---- Defaults matching docker-compose.yml ----
    private static final String DEFAULT_FRONTEND_URL = "http://localhost:3000";
    private static final String DEFAULT_API_URL = "http://localhost:8080";
    private static final String DEFAULT_ADMIN_EMAIL = "cto+1@loaders.dev";
    private static final String DEFAULT_ADMIN_PASSWORD = "alejano123";
    private static final boolean DEFAULT_HEADED = false;
    private static final String DEFAULT_BROWSER = "chromium";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READINESS_TIMEOUT_MS = 120_000;
    private static final String DEFAULT_TAGS = "@smoke";
    private static final boolean DEFAULT_TRACE = false;

    // ---- DB defaults matching docker-compose.yml (postgres:5432, foremen/foremen) ----
    // The Postgres port is published to the host as ${POSTGRES_PORT:-5432}, and the
    // database/user/password all default to "foremen" in docker-compose.yml.
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/foremen";
    private static final String DEFAULT_DB_USER = "foremen";
    private static final String DEFAULT_DB_PASSWORD = "foremen";
    private static final boolean DEFAULT_CREATE_MISSING_ROLE = true;

    // ---- Reporting defaults (Requirement 10) ----
    private static final String DEFAULT_REPORT_DIR = "build/qa-report";
    private static final String DEFAULT_SHOTS = "per-step";
    private static final boolean DEFAULT_FULLPAGE_SHOTS = true;
    private static final boolean DEFAULT_REPORT_KEEP_HISTORY = true;

    private TestConfig() {
    }

    /** Frontend base URL, e.g. {@code http://localhost:3000}. */
    public static String frontendUrl() {
        return stripTrailingSlash(resolve(KEY_FRONTEND_URL, DEFAULT_FRONTEND_URL));
    }

    /** API base URL, e.g. {@code http://localhost:8080}. */
    public static String apiUrl() {
        return stripTrailingSlash(resolve(KEY_API_URL, DEFAULT_API_URL));
    }

    /** Seeded ADMIN email used to authenticate. */
    public static String adminEmail() {
        return resolve(KEY_ADMIN_EMAIL, DEFAULT_ADMIN_EMAIL);
    }

    /** Seeded ADMIN password used to authenticate. */
    public static String adminPassword() {
        return resolve(KEY_ADMIN_PASSWORD, DEFAULT_ADMIN_PASSWORD);
    }

    /** {@code true} to run the browser headed (visible); default is headless. */
    public static boolean headed() {
        return resolveBoolean(KEY_HEADED, DEFAULT_HEADED);
    }

    /** Browser engine name: {@code chromium} (default), {@code firefox}, or {@code webkit}. */
    public static String browser() {
        return resolve(KEY_BROWSER, DEFAULT_BROWSER).trim().toLowerCase();
    }

    /** Default action/navigation timeout in milliseconds. */
    public static int timeoutMs() {
        return resolveInt(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }

    /** Maximum time to wait for the Docker stack to become ready, in milliseconds. */
    public static int readinessTimeoutMs() {
        return resolveInt(KEY_READINESS_TIMEOUT_MS, DEFAULT_READINESS_TIMEOUT_MS);
    }

    /** Cucumber tag filter expression, default {@code @smoke}. */
    public static String tags() {
        return resolve(KEY_TAGS, DEFAULT_TAGS);
    }

    /** {@code true} to record a Playwright trace per scenario. */
    public static boolean trace() {
        return resolveBoolean(KEY_TRACE, DEFAULT_TRACE);
    }

    /**
     * JDBC URL of the test database used for direct test-user provisioning (Requirement 12.10).
     * Defaults to {@code jdbc:postgresql://localhost:5432/foremen} per {@code docker-compose.yml}.
     */
    public static String dbUrl() {
        return resolve(KEY_DB_URL, DEFAULT_DB_URL);
    }

    /**
     * Test-database username. Resolves {@code QA_DB_USER} first, then {@code POSTGRES_USER}, then the
     * {@code foremen} default from {@code docker-compose.yml} (Requirement 12.10).
     */
    public static String dbUser() {
        String primary = resolve(KEY_DB_USER, null);
        if (primary != null) {
            return primary;
        }
        return resolve("POSTGRES_USER", DEFAULT_DB_USER);
    }

    /**
     * Test-database password. Resolves {@code QA_DB_PASSWORD} first, then {@code POSTGRES_PASSWORD},
     * then the {@code foremen} default from {@code docker-compose.yml} (Requirement 12.10).
     */
    public static String dbPassword() {
        String primary = resolve(KEY_DB_PASSWORD, null);
        if (primary != null) {
            return primary;
        }
        return resolve("POSTGRES_PASSWORD", DEFAULT_DB_PASSWORD);
    }

    /**
     * When a requested custom role code does not exist, {@code true} (default) lets the fixture create
     * it (and register it for teardown); {@code false} makes the create step fail with a clear message
     * (Requirement 12.9). System roles are never created or deleted regardless of this flag.
     */
    public static boolean createMissingRole() {
        return resolveBoolean(KEY_CREATE_MISSING_ROLE, DEFAULT_CREATE_MISSING_ROLE);
    }

    // ---- Reporting (Requirement 10) ----

    /**
     * Root output directory for reports (Requirement 10.9). Run-scoped folders (keyed by run-id) are
     * created underneath. Default {@code build/qa-report}.
     */
    public static String reportDir() {
        return stripTrailingSlash(resolve(KEY_REPORT_DIR, DEFAULT_REPORT_DIR));
    }

    /**
     * Screenshot mode: {@code per-step} (default — a shot after every Gherkin step for the demo
     * report) or {@code on-failure} (only capture on a failing/problem step, for fast CI runs).
     */
    public static String shots() {
        return resolve(KEY_SHOTS, DEFAULT_SHOTS).trim().toLowerCase();
    }

    /** {@code true} (default) to capture full-page screenshots rather than just the viewport. */
    public static boolean fullPageShots() {
        return resolveBoolean(KEY_FULLPAGE_SHOTS, DEFAULT_FULLPAGE_SHOTS);
    }

    /**
     * {@code true} (default) to preserve historical run folders (one per run-id). When {@code false}
     * the writer may reuse/overwrite a {@code latest} folder for a single rolling report.
     */
    public static boolean reportKeepHistory() {
        return resolveBoolean(KEY_REPORT_KEEP_HISTORY, DEFAULT_REPORT_KEEP_HISTORY);
    }

    // ---- Resolution helpers ----

    /**
     * Resolves a value by system property, then environment variable, then the supplied default.
     * Blank values are treated as absent so an empty {@code -DKEY=} does not shadow the default.
     */
    public static String resolve(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (isPresent(prop)) {
            return prop.trim();
        }
        String env = System.getenv(key);
        if (isPresent(env)) {
            return env.trim();
        }
        return defaultValue;
    }

    private static boolean resolveBoolean(String key, boolean defaultValue) {
        String raw = resolve(key, Boolean.toString(defaultValue));
        return Boolean.parseBoolean(raw.trim());
    }

    private static int resolveInt(String key, int defaultValue) {
        String raw = resolve(key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Invalid integer for configuration key '" + key + "': '" + raw + "'", e);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.length() > 1 && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
