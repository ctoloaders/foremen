package com.foremen.qa.support;

import com.microsoft.playwright.Page;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cucumber lifecycle hooks wiring the browser to each scenario (Requirements 2.2, 3.4).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link BeforeAll}: gate the whole run on the live stack being ready ({@link WaitFor}),
 *       failing fast with a clear message otherwise.</li>
 *   <li>{@link Before}: open a fresh incognito {@link com.microsoft.playwright.BrowserContext}
 *       + {@link Page} so storage never leaks between scenarios.</li>
 *   <li>{@link After}: close the scenario context (flushing the optional Playwright trace to a
 *       run-scoped path), releasing the incognito sandbox.</li>
 * </ul>
 *
 * <p>The active {@link Page} is exposed via {@link #currentPage()} for step definitions and the
 * (later) {@code World} holder to consume. A {@link ThreadLocal} keeps the wiring safe if Cucumber
 * ever runs scenarios in parallel.
 */
public class Hooks {

    private static final ThreadLocal<PlaywrightFactory.Session> CURRENT_SESSION = new ThreadLocal<>();
    private static final AtomicInteger SCENARIO_SEQ = new AtomicInteger();

    /** Per-scenario shared state + teardown registry, injected by Cucumber (picocontainer). */
    private final World world;

    /**
     * Cucumber instantiates this per scenario and injects the same {@link World} shared with the
     * step-definition classes, so teardown callbacks registered by steps are visible here.
     */
    public Hooks(World world) {
        this.world = world;
    }

    /** Run once before any scenario: fail fast unless the Docker stack is up and healthy. */
    @BeforeAll
    public static void ensureStackReady() {
        WaitFor.stackIsReady();
    }

    /**
     * Run once after all scenarios: release shared run-scoped resources — the JDBC pool used for
     * test-user provisioning and the Playwright browser. Both are lazily opened, so this is a no-op
     * when a run never touched them.
     */
    @io.cucumber.java.AfterAll
    public static void shutdownRunResources() {
        try {
            com.foremen.qa.fixtures.Db.shutdown();
        } finally {
            PlaywrightFactory.shutdown();
        }
    }

    /** Open a fresh incognito context + page for this scenario. */
    @Before(order = 0)
    public void openBrowser(Scenario scenario) {
        PlaywrightFactory.Session session = PlaywrightFactory.newSession();
        CURRENT_SESSION.set(session);
    }

    /**
     * Run the scenario's registered cleanup callbacks (LIFO) and dispose the API helper.
     *
     * <p>Ordered higher than {@link #closeBrowser(Scenario)} so it runs first — teardown talks to
     * the API, not the browser, and running it before the context closes keeps the browser page
     * available for any final capture. Failures in individual callbacks are collected and swallowed
     * so every callback still runs (Requirement 3.2); a summary is attached to the scenario for
     * diagnostics without failing an otherwise-green scenario.
     */
    @After(order = 10)
    public void runTeardown(Scenario scenario) {
        try {
            java.util.List<Throwable> failures = world.runTeardown();
            if (!failures.isEmpty()) {
                StringBuilder sb = new StringBuilder("Teardown encountered ")
                        .append(failures.size()).append(" failure(s):");
                for (Throwable t : failures) {
                    sb.append("\n  - ").append(t.getMessage());
                }
                scenario.log(sb.toString());
            }
        } finally {
            world.closeApi();
        }
    }

    /** Close the scenario context, flushing the optional trace to a run-scoped file. */
    @After(order = 0)
    public void closeBrowser(Scenario scenario) {
        PlaywrightFactory.Session session = CURRENT_SESSION.get();
        if (session == null) {
            return;
        }
        try {
            Path tracePath = TestConfig.trace() ? traceFileFor(scenario) : null;
            session.close(tracePath);
        } finally {
            CURRENT_SESSION.remove();
        }
    }

    /**
     * The active scenario's {@link Page}.
     *
     * @throws IllegalStateException if called outside a scenario (no session open)
     */
    public static Page currentPage() {
        PlaywrightFactory.Session session = CURRENT_SESSION.get();
        if (session == null) {
            throw new IllegalStateException(
                    "No active browser session; currentPage() must be called within a scenario.");
        }
        return session.page();
    }

    /** The active scenario's session (context + page), or {@code null} outside a scenario. */
    public static PlaywrightFactory.Session currentSession() {
        return CURRENT_SESSION.get();
    }

    private static Path traceFileFor(Scenario scenario) {
        String slug = slug(scenario.getName());
        String file = SCENARIO_SEQ.incrementAndGet() + "-" + slug + ".zip";
        return Paths.get("build", "qa-report", "traces", file);
    }

    private static String slug(String name) {
        if (name == null || name.isBlank()) {
            return "scenario";
        }
        String slug = name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "scenario" : slug;
    }
}
