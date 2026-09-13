package com.foremen.qa.support;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-scenario shared state and teardown registry (Requirements 3.1, 3.2, 3.3).
 *
 * <p>Cucumber (via picocontainer) creates one {@code World} per scenario and injects the same
 * instance into every step-definition class and into {@link Hooks} that declare it as a constructor
 * dependency. Steps use it to hand data forward (the created role id, the current test user's
 * credentials, created dictionary/project ids) and — most importantly — to <b>register cleanup
 * callbacks</b> that {@link Hooks} runs after the scenario.
 *
 * <h2>LIFO teardown</h2>
 * Cleanup callbacks are executed in <b>last-in, first-out</b> order in the {@code @After} hook. LIFO
 * matches creation order for FK-dependent data: if a scenario creates a role, then a user in that
 * role, then a project owned by the user, teardown removes the project first, then the user, then the
 * role — never violating a foreign key. Each callback is isolated: a failure in one is recorded and
 * swallowed so the remaining callbacks still run and the suite converges to a clean state even after
 * a partial failure (Requirement 3.2). This makes runs repeatable without manual DB cleanup
 * (Requirement 3.5).
 *
 * <p>Only run-created resources are ever registered for teardown; the seeded ADMIN and seed
 * reference data are never touched (Requirement 3.3).
 */
public class World {

    /** Cleanup callbacks, executed newest-first (LIFO) in the {@code @After} hook. */
    private final Deque<Runnable> teardownStack = new ArrayDeque<>();

    /** The API helper (lazily created) reused by steps for setup/teardown HTTP. */
    private ApiHelper apiHelper;

    /**
     * Provisioned test users keyed by name. The unnamed "current test user" (created via the
     * role-only step) is stored under {@link #CURRENT_USER_KEY}; named users are stored under their
     * given name for multi-user scenarios. Populated by the test-user steps (Requirement 12.4).
     */
    private final java.util.Map<String, com.foremen.qa.fixtures.TestUser> testUsers =
            new java.util.LinkedHashMap<>();

    /** Reserved key for the single "current" test user created by the role-only Gherkin step. */
    public static final String CURRENT_USER_KEY = "__current__";

    /**
     * The code of a run-created custom role set up by a scenario (e.g. the restricted role granting
     * only {@code USERS:READ} in the menu-visibility slice). Lets a later step provision a test user
     * in that dynamically-generated role without hard-coding its code.
     */
    private String restrictedRoleCode;

    /**
     * Register a cleanup action to run after the scenario. Actions run in reverse registration order
     * (LIFO), so registering in creation order yields FK-safe teardown.
     *
     * @param teardown the cleanup callback; must be idempotent (a missing row should be a no-op)
     */
    public void registerTeardown(Runnable teardown) {
        if (teardown != null) {
            teardownStack.push(teardown);
        }
    }

    /**
     * Run all registered teardown callbacks LIFO, isolating failures so every callback still runs.
     * Called by {@link Hooks} in the {@code @After} hook. Clears the stack afterward.
     *
     * @return a list of failures encountered (empty when all cleanups succeeded), for diagnostics
     */
    public java.util.List<Throwable> runTeardown() {
        java.util.List<Throwable> failures = new java.util.ArrayList<>();
        while (!teardownStack.isEmpty()) {
            Runnable action = teardownStack.pop();
            try {
                action.run();
            } catch (RuntimeException | AssertionError e) {
                failures.add(e);
            }
        }
        return failures;
    }

    /** Number of pending teardown actions (primarily for assertions/diagnostics). */
    public int pendingTeardowns() {
        return teardownStack.size();
    }

    /**
     * The shared {@link ApiHelper} for this scenario, created on first use against the configured API
     * base URL. Reused by all steps in the scenario; closed by {@link Hooks} in {@code @After}.
     */
    public ApiHelper api() {
        if (apiHelper == null) {
            apiHelper = ApiHelper.create();
        }
        return apiHelper;
    }

    // ---- Provisioned test users (Requirement 12.4) ----

    /**
     * Remember a provisioned test user under a name so later steps can reference it. The role-only
     * create step uses {@link #CURRENT_USER_KEY}; named create steps use the supplied name.
     */
    public void putTestUser(String name, com.foremen.qa.fixtures.TestUser user) {
        testUsers.put(name, user);
    }

    /** The test user stored under {@code name}, or {@code null} if none was created under it. */
    public com.foremen.qa.fixtures.TestUser testUser(String name) {
        return testUsers.get(name);
    }

    /**
     * The "current" test user (the one created by the role-only step), or {@code null} if none.
     * Convenience for the common single-user scenario.
     */
    public com.foremen.qa.fixtures.TestUser currentTestUser() {
        return testUsers.get(CURRENT_USER_KEY);
    }

    // ---- Run-created restricted role (menu-visibility slice) ----

    /** Remember the code of a run-created custom role a scenario just set up. */
    public void putRestrictedRoleCode(String code) {
        this.restrictedRoleCode = code;
    }

    /** The run-created custom role code set up by the current scenario, or {@code null}. */
    public String restrictedRoleCode() {
        return restrictedRoleCode;
    }

    /** Dispose the API helper if one was created. Called by {@link Hooks} after teardown. */
    public void closeApi() {
        if (apiHelper != null) {
            apiHelper.close();
            apiHelper = null;
        }
    }
}
