package com.foremen.qa.steps;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TestConfig;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable Gherkin steps to create, UI-login as, and delete a role-based test user (Requirement 12).
 *
 * <p>These are the building blocks other features compose to render role-specific UI:
 * <ul>
 *   <li><b>Create</b> — {@code Given a test user with role "X" exists} (and a named variant) creates
 *       an ACTIVE, password-known user directly in the DB, stores its credentials in {@link World},
 *       and registers automatic teardown so even scenarios that omit the explicit delete step are
 *       cleaned up (Requirement 12.4, 12.7).</li>
 *   <li><b>Login</b> — {@code When I log in through the UI as the test user} drives the frontend
 *       {@code /login} form with the remembered credentials. The provisioning path is the only
 *       reliable way to obtain an ACTIVE, password-known user per role (Requirement 12.5).</li>
 *   <li><b>Delete</b> — {@code Then the test user with role "X" is deleted with all its resources}
 *       runs the FK-safe cascade cleanup immediately (also covered by automatic teardown;
 *       Requirement 12.6).</li>
 * </ul>
 *
 * <p>All provisioning lives here + in {@link TestUserFixture}; nothing is added to the application
 * (Requirement 12.8). System roles are never created or deleted; custom {@code TESTROLE_*} roles are
 * created/removed per configuration (Requirement 12.9).
 */
public class TestUserSteps {

    private final World world;
    private final TestUserFixture fixture;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public TestUserSteps(World world) {
        this.world = world;
        this.fixture = new TestUserFixture();
    }

    // ---- Create (Requirement 12.1–12.5, 12.7) ----

    /**
     * Create the "current" ACTIVE test user in the given role and remember it in {@link World}.
     * Registers FK-safe teardown immediately so the user is cleaned up even without the explicit
     * delete step (Requirement 12.7).
     */
    @Given("a test user with role {string} exists")
    public void aTestUserWithRoleExists(String roleCode) {
        provision(World.CURRENT_USER_KEY, roleCode);
    }

    /**
     * Create a named ACTIVE test user in the given role for multi-user scenarios, remembering it
     * under {@code name} in {@link World} and registering teardown.
     */
    @Given("a test user {string} with role {string} exists")
    public void aNamedTestUserWithRoleExists(String name, String roleCode) {
        provision(name, roleCode);
    }

    private void provision(String key, String roleCode) {
        TestUser user = fixture.createActiveUser(roleCode);
        world.putTestUser(key, user);
        // LIFO teardown: idempotent cascade cleanup (missing rows are a no-op), so an explicit
        // delete step followed by this automatic teardown is safe (Requirement 12.6, 12.7).
        world.registerTeardown(() -> fixture.deleteUserAndResources(user));
    }

    // ---- UI login (Requirement 12.5) ----

    /**
     * Log in through the frontend {@code /login} form as the current test user, using its remembered
     * email + password. Lands on the app after a successful password login.
     */
    @When("I log in through the UI as the test user")
    public void iLogInThroughTheUiAsTheTestUser() {
        loginAs(requireUser(World.CURRENT_USER_KEY, "current"));
    }

    /**
     * Log in through the frontend {@code /login} form as the named test user (multi-user scenarios).
     */
    @When("I log in through the UI as the test user {string}")
    public void iLogInThroughTheUiAsTheNamedTestUser(String name) {
        loginAs(requireUser(name, name));
    }

    private void loginAs(TestUser user) {
        Page page = Hooks.currentPage();
        page.navigate(TestConfig.frontendUrl() + "/login");
        // Selectors mirror the frontend LoginPage (#login-email / #login-password + submit button).
        page.locator("#login-email").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        page.locator("#login-email").fill(user.email());
        page.locator("#login-password").fill(user.password());
        page.locator("button[type=submit]").click();
        // After a successful login the app navigates away from /login. Wait for that transition so
        // the next step sees the authenticated UI (no fixed sleeps; relies on URL change).
        page.waitForURL(url -> !url.contains("/login"));
    }

    // ---- Delete (Requirement 12.6) ----

    /**
     * Delete the current test user and all its FK-dependent resources immediately. Idempotent, and
     * also invoked automatically by teardown, so scenarios may call it explicitly to demonstrate the
     * cleanup without risk of a double-delete failure.
     */
    @Then("the test user with role {string} is deleted with all its resources")
    public void theTestUserWithRoleIsDeleted(String roleCode) {
        TestUser user = requireUser(World.CURRENT_USER_KEY, "current");
        assertThat(user.roleCode())
                .as("current test user role should match the step's role")
                .isEqualTo(roleCode);
        fixture.deleteUserAndResources(user);
    }

    /** Delete a named test user and all its FK-dependent resources immediately. */
    @Then("the test user {string} is deleted with all its resources")
    public void theNamedTestUserIsDeleted(String name) {
        fixture.deleteUserAndResources(requireUser(name, name));
    }

    private TestUser requireUser(String key, String label) {
        TestUser user = world.testUser(key);
        if (user == null) {
            throw new IllegalStateException(
                    "No " + label + " test user has been created; use a "
                            + "\"a test user with role ... exists\" step first.");
        }
        return user;
    }
}
