package com.foremen.qa.steps;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.ForbiddenPage;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-03 menu-visibility & authorization smoke steps (Requirements 6.1, 6.2, 6.3, 6.4).
 *
 * <p>The frontend filters every navigation surface (sidebar, drawer, bottom nav) through a single
 * permission rule: an item is visible only when the user is granted its {@code (resource, operation)}
 * requirement, or when it carries none (the dashboard {@code /} and appearance settings are always
 * visible). This slice verifies three things through the browser via {@link AppShell} and
 * {@link ForbiddenPage}:
 * <ul>
 *   <li>ADMIN sees the primary navigation and the current role in the shell (6.1);</li>
 *   <li>a user whose role grants only {@code USERS:READ} sees the unrestricted + USERS items but not
 *       items for resources it lacks (6.2);</li>
 *   <li>that restricted user, deep-linking to a forbidden route, is redirected to {@code /403} with a
 *       working Go_Back control (6.3).</li>
 * </ul>
 *
 * <p>The restricted-role setup/teardown (create role + grant {@code USERS:READ} + create/activate a
 * user in it, then remove) uses {@link ApiHelper} for the role/matrix and the same
 * {@link TestUserFixture} the reusable {@link TestUserSteps} use for the user, registering LIFO
 * teardown so the run stays self-cleaning (Requirement 6.4 / 3). The reusable
 * {@code When I log in through the UI as the test user} step ({@link TestUserSteps}) is composed
 * unchanged — this class only adds the dynamic restricted-role provisioning and the visibility/guard
 * assertions.
 */
public class NavVisibilitySteps {

    private final World world;
    private final TestUserFixture fixture;

    private AppShell appShell;
    private ForbiddenPage forbiddenPage;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public NavVisibilitySteps(World world) {
        this.world = world;
        this.fixture = new TestUserFixture();
    }

    private AppShell appShell() {
        if (appShell == null) {
            appShell = new AppShell(Hooks.currentPage());
        }
        return appShell;
    }

    private ForbiddenPage forbiddenPage() {
        if (forbiddenPage == null) {
            forbiddenPage = new ForbiddenPage(Hooks.currentPage());
        }
        return forbiddenPage;
    }

    // ---- Admin visibility (Requirement 6.1) ----

    /**
     * Assert the primary navigation groups are usable: the shell exposes the unrestricted dashboard
     * plus the system group's {@code /users} and {@code /roles} items, which the seeded ADMIN
     * (grant-all) always sees. Asserting a representative subset avoids coupling the check to the
     * full nav list while still proving grouped navigation renders.
     */
    @Then("the primary navigation groups are visible")
    public void thePrimaryNavigationGroupsAreVisible() {
        AppShell shell = appShell().waitUntilRendered();
        assertThat(shell.isRendered())
                .as("the authenticated app shell should render")
                .isTrue();
        for (String route : List.of("/", "/users", "/roles")) {
            assertThat(shell.isNavItemVisible(route))
                    .as("ADMIN should see the primary navigation item for route %s", route)
                    .isTrue();
        }
    }

    /**
     * Assert the current user's role is shown in the shell (the sidebar/drawer user footer renders
     * name + role code). For the seeded ADMIN this is the {@code ADMIN} role code.
     */
    @Then("the current role name is shown in the shell")
    public void theCurrentRoleNameIsShownInTheShell() {
        assertThat(appShell().roleBadgeText())
                .as("the current role should be shown in the shell")
                .isNotBlank();
    }

    // ---- Restricted-role setup (Requirement 6.4) ----

    /**
     * Create a run-unique custom role granting exactly the given single {@code (resource, operation)}
     * — e.g. {@code USERS:READ} — via the API helper as the seeded ADMIN, and remember its code in
     * {@link World} so the next step can provision a user in it. Registers LIFO teardown to delete
     * the role after the scenario.
     */
    @Given("a role granting only {string} {string} exists")
    public void aRoleGrantingOnlyExists(String resourceCode, String operationCode) {
        ApiHelper api = world.api();
        api.loginAdmin();
        String roleCode = DataGen.nextRoleCode();
        long roleId = api.createRole(roleCode);
        // Delete the role in teardown (registered before the user so LIFO removes the user first).
        world.registerTeardown(() -> world.api().deleteRole(roleId));

        long resourceId = api.resolveResourceId(resourceCode);
        long operationId = api.resolveOperationId(operationCode);
        api.setRolePermissions(roleId,
                List.of(new ApiHelper.PermissionEntry(resourceId, List.of(operationId))));

        world.putRestrictedRoleCode(roleCode);
    }

    /**
     * Provision an ACTIVE, login-ready test user in the run-created restricted role (whose code was
     * stored by the previous step), remember it as the current test user, and register FK-safe
     * teardown. Reuses the same {@link TestUserFixture} as {@link TestUserSteps}, storing the user
     * under {@link World#CURRENT_USER_KEY} so the reusable
     * {@code When I log in through the UI as the test user} step works unchanged.
     */
    @Given("a test user in the restricted role exists")
    public void aTestUserInTheRestrictedRoleExists() {
        String roleCode = world.restrictedRoleCode();
        assertThat(roleCode)
                .as("a restricted role must be created before provisioning a user in it")
                .isNotNull();
        TestUser user = fixture.createActiveUser(roleCode);
        world.putTestUser(World.CURRENT_USER_KEY, user);
        world.registerTeardown(() -> fixture.deleteUserAndResources(user));
    }

    // ---- Restricted-role visibility (Requirement 6.2) ----

    @Then("the navigation item for route {string} is visible")
    public void theNavigationItemForRouteIsVisible(String route) {
        appShell().waitUntilRendered();
        assertThat(appShell().isNavItemVisible(route))
                .as("navigation item for route %s should be visible", route)
                .isTrue();
    }

    @Then("the navigation item for route {string} is hidden")
    public void theNavigationItemForRouteIsHidden(String route) {
        appShell().waitUntilRendered();
        assertThat(appShell().isNavItemVisible(route))
                .as("navigation item for route %s should be hidden for a role lacking its resource",
                        route)
                .isFalse();
    }

    // ---- Forbidden deep-link round-trip (Requirement 6.3) ----

    /**
     * Deep-link (by URL) to a route the current user is not granted. The nav item is hidden, but the
     * route is still reachable by URL, exercising the route guard.
     */
    @When("I open the deep-link {string}")
    public void iOpenTheDeepLink(String route) {
        appShell().openRoute(route);
    }

    @Then("I am shown the forbidden page")
    public void iAmShownTheForbiddenPage() {
        Page page = Hooks.currentPage();
        page.waitForURL(url -> url.contains(ForbiddenPage.ROUTE));
        assertThat(forbiddenPage().isShown())
                .as("a forbidden deep-link should redirect the restricted user to /403")
                .isTrue();
    }

    @When("I click Go Back on the forbidden page")
    public void iClickGoBackOnTheForbiddenPage() {
        forbiddenPage().goBack();
    }

    @Then("I am taken away from the forbidden page")
    public void iAmTakenAwayFromTheForbiddenPage() {
        Page page = Hooks.currentPage();
        page.waitForURL(url -> !url.contains(ForbiddenPage.ROUTE));
        assertThat(page.url())
                .as("Go_Back should leave the /403 forbidden route")
                .doesNotContain(ForbiddenPage.ROUTE);
    }
}
