package com.foremen.qa.steps;

import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.DataTablePage;
import com.foremen.qa.pages.RoleMatrix;
import com.foremen.qa.pages.RolesPage;
import com.foremen.qa.pages.ThemeSettingsPage;
import com.foremen.qa.pages.UserFormSheet;
import com.foremen.qa.pages.UsersPage;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-02 admin-panel smoke steps (Requirements 4.1, 7.1, 7.2, 7.3, 7.4, 9.2).
 *
 * <p>This slice exercises the SPA admin foundation through the browser as the seeded ADMIN:
 * <ul>
 *   <li><b>Shell</b> (7.1) — the authenticated app shell renders and its primary navigation is
 *       usable (nav to Users/Roles works via {@link AppShell}).</li>
 *   <li><b>Roles</b> (7.2) — the roles list renders and the access/permission matrix (resource ×
 *       operation controls) is shown ({@link RolesPage}/{@link RoleMatrix}).</li>
 *   <li><b>Users</b> (7.3) — the users list renders; creating a user with a run-unique generated
 *       email makes it appear in the list; the created user is deactivated in teardown (via the same
 *       {@code DELETE /api/users/{id}} the UI uses), keeping the run self-cleaning (Requirement 3).
 *       This create → verify → deactivate lifecycle also transitively exercises the FOR-01 CRUD
 *       framework (Requirement 9.2).</li>
 *   <li><b>Theme</b> (7.4) — toggling the appearance theme persists across a full page reload
 *       ({@link ThemeSettingsPage}).</li>
 * </ul>
 *
 * <p>The stack-ready background and the "log in as the seeded admin" step are reused from
 * {@link CommonSteps} — this class only adds the admin-panel navigation, assertions, and the
 * user-create teardown wiring. Assertions live here; page objects own all selectors.
 */
public class AdminPanelSteps {

    private final World world;

    private AppShell appShell;
    private UsersPage usersPage;
    private RolesPage rolesPage;
    private RoleMatrix roleMatrix;
    private ThemeSettingsPage themeSettingsPage;

    /** Email of the user created by the users-create scenario, for the list assertion. */
    private String createdUserEmail;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public AdminPanelSteps(World world) {
        this.world = world;
    }

    private Page page() {
        return Hooks.currentPage();
    }

    private AppShell appShell() {
        if (appShell == null) {
            appShell = new AppShell(page());
        }
        return appShell;
    }

    // ---- Shell (Requirement 7.1) ----

    @Then("the application shell renders")
    public void theApplicationShellRenders() {
        AppShell shell = appShell().waitUntilRendered();
        assertThat(shell.isRendered())
                .as("the authenticated app shell (topbar + navigation) should render")
                .isTrue();
    }

    @Then("the primary navigation is usable")
    public void thePrimaryNavigationIsUsable() {
        AppShell shell = appShell();
        // Prove navigation is operable by clicking through to two primary routes and back home.
        for (String route : new String[] {UsersPage.ROUTE, RolesPage.ROUTE}) {
            assertThat(shell.isNavItemVisible(route))
                    .as("ADMIN should see the primary navigation item for %s", route)
                    .isTrue();
            shell.navigateTo(route);
            assertThat(shell.isOnRoute(route))
                    .as("navigating to %s should land on it", route)
                    .isTrue();
        }
    }

    // ---- Roles (Requirement 7.2) ----

    @When("I open the Roles page")
    public void iOpenTheRolesPage() {
        rolesPage = new RolesPage(page()).open();
    }

    @Then("the roles list renders")
    public void theRolesListRenders() {
        DataTablePage table = rolesPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the roles list table should render")
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the roles list should render at least one column header")
                .isNotEmpty();
    }

    @When("I open the access matrix")
    public void iOpenTheAccessMatrix() {
        roleMatrix = rolesPage.openMatrixTab();
    }

    @Then("the access matrix is shown")
    public void theAccessMatrixIsShown() {
        assertThat(roleMatrix.isVisible())
                .as("the roles access matrix (resource x operation controls) should be visible")
                .isTrue();
        assertThat(roleMatrix.resourceColumnCount())
                .as("the access matrix should render resource columns")
                .isGreaterThan(0);
        assertThat(roleMatrix.roleRowCount())
                .as("the access matrix should render at least one role row")
                .isGreaterThan(0);
    }

    // ---- Users (Requirement 7.3, 9.2) ----

    @When("I open the Users page")
    public void iOpenTheUsersPage() {
        usersPage = new UsersPage(page()).open();
    }

    @Then("the users list renders")
    public void theUsersListRenders() {
        DataTablePage table = usersPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the users list table should render")
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the users list should render at least one column header")
                .isNotEmpty();
    }

    /**
     * Create a user with a run-unique generated email via the create sheet, and register teardown
     * (deactivate via {@code DELETE /api/users/{id}}) immediately so the run stays self-cleaning even
     * if a later assertion fails. Picks the first available (non-excluded) role and a locale.
     */
    @When("I create a user with a generated email")
    public void iCreateAUserWithAGeneratedEmail() {
        createdUserEmail = DataGen.nextEmail();

        // Register teardown up-front (before the create can fail mid-way): resolve the id by email
        // and deactivate. Idempotent — a not-found user is a no-op via the API helper's 404 handling.
        final String email = createdUserEmail;
        world.registerTeardown(() -> {
            ApiHelper api = world.api();
            api.loginAdmin();
            try {
                long id = api.resolveUserIdByEmail(email);
                api.deactivateUser(id);
            } catch (RuntimeException notFound) {
                // User never persisted (create failed before commit) — nothing to clean up.
            }
        });

        UserFormSheet sheet = usersPage.openCreate();
        sheet.fillName("QA " + email)
                .fillEmail(email)
                .selectLocale("pl")
                .selectFirstRole()
                .submit();
        // On success the sheet closes; wait for that transition before asserting the list.
        sheet.waitUntilClosed();
    }

    @Then("the created user appears in the users list")
    public void theCreatedUserAppearsInTheUsersList() {
        assertThat(createdUserEmail)
                .as("a user must have been created before asserting it appears")
                .isNotNull();
        DataTablePage table = usersPage.table().waitUntilLoaded();
        // Search by the generated email to surface the just-created row regardless of pagination.
        table.search(createdUserEmail);
        Page page = page();
        // Web-first: wait for the row containing the email to be present after the debounced search.
        page.waitForCondition(() -> table.containsRow(createdUserEmail));
        assertThat(table.containsRow(createdUserEmail))
                .as("the newly created user %s should appear in the users list", createdUserEmail)
                .isTrue();
    }

    // ---- Theme (Requirement 7.4) ----

    @When("I open the Appearance settings page")
    public void iOpenTheAppearanceSettingsPage() {
        themeSettingsPage = new ThemeSettingsPage(page()).open();
    }

    /**
     * Toggle the theme to the opposite explicit mode (dark↔light) so the {@code dark} class change is
     * unambiguous and independent of the OS {@code prefers-color-scheme}. Remembers the target mode in
     * the step's field for the post-reload assertion.
     */
    private String targetMode;

    @When("I toggle the theme mode")
    public void iToggleTheThemeMode() {
        String current = themeSettingsPage.currentMode();
        // Choose an explicit opposite; if currently dark go light, otherwise go dark.
        targetMode = ThemeSettingsPage.MODE_DARK.equals(current)
                ? ThemeSettingsPage.MODE_LIGHT
                : ThemeSettingsPage.MODE_DARK;
        themeSettingsPage.selectMode(targetMode);
        assertThat(themeSettingsPage.isModeSelected(targetMode))
                .as("the theme mode should switch to %s after toggling", targetMode)
                .isTrue();
        // The mode radios are live-preview only; commit the durable preference to the backend by
        // clicking "Zapisz preferencje" so it survives the re-hydrate-from-server on reload.
        themeSettingsPage.savePreferences();
    }

    @Then("the theme selection persists across a page reload")
    public void theThemeSelectionPersistsAcrossAReload() {
        assertThat(targetMode).as("a theme toggle must have run first").isNotNull();

        // Before reload: the persisted preference and the applied class reflect the target mode.
        assertThat(themeSettingsPage.persistedThemeMode())
                .as("the toggled theme mode should be persisted to localStorage")
                .isEqualTo(targetMode);
        assertThat(themeSettingsPage.isDarkClassApplied())
                .as("the applied dark class should match the %s mode before reload", targetMode)
                .isEqualTo(ThemeSettingsPage.MODE_DARK.equals(targetMode));

        themeSettingsPage.reload();

        // After a full reload: the selection, the persisted value, and the applied class all survive.
        assertThat(themeSettingsPage.isModeSelected(targetMode))
                .as("the %s theme mode should still be selected after reload", targetMode)
                .isTrue();
        assertThat(themeSettingsPage.persistedThemeMode())
                .as("the persisted theme mode should survive the reload")
                .isEqualTo(targetMode);
        assertThat(themeSettingsPage.isDarkClassApplied())
                .as("the applied dark class should survive the reload for %s mode", targetMode)
                .isEqualTo(ThemeSettingsPage.MODE_DARK.equals(targetMode));
    }
}
