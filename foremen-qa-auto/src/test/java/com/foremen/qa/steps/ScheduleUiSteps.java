package com.foremen.qa.steps;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.pages.EstimateTab;
import com.foremen.qa.pages.ProjectWorkspacePage;
import com.foremen.qa.pages.ScheduleTab;
import com.foremen.qa.pages.WorkspaceTabs;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.ScheduleApiHelper;
import com.foremen.qa.support.ScheduleApiHelper.Resp;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-10 (browser UI) steps for the FOR-05-10 Planning Gantt (Harmonogram) tab.
 *
 * <p>Seeds a design-stage project with a one-room estimate (so the schedule derives rows from the
 * estimate's work categories) plus two ACTIVE workers via API, then drives the real browser
 * {@code scheduleDesign} tab: routing + visibility + i18n, the view (rows / volume / week+day /
 * calendar / money masking / no-start / empty), editing (drag / keyboard / dialog / schedule-clear /
 * auto-create / read-only), the readiness chip, and localization. Estimate lines are produced the
 * way a user does it — applying a package through {@link EstimateTab} — so the categories are real.
 *
 * <p>Repeatability: {@link DataGen} run-id + LIFO teardown in {@link World}.
 */
public class ScheduleUiSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();

    private ScheduleApiHelper scheduleApi;
    private ScheduleTab scheduleTab;
    private EstimateTab estimateTab;
    private ProjectWorkspacePage workspace;
    private WorkspaceTabs tabs;

    private Long projectId;
    private final Map<String, TestUser> users = new LinkedHashMap<>();

    public ScheduleUiSteps(World world) {
        this.world = world;
    }

    // ---- lazily-built helpers / page objects ----

    private Page page() {
        return Hooks.currentPage();
    }

    private ScheduleTab scheduleTab() {
        if (scheduleTab == null) {
            scheduleTab = new ScheduleTab(page());
        }
        return scheduleTab;
    }

    private EstimateTab estimateTab() {
        if (estimateTab == null) {
            estimateTab = new EstimateTab(page());
        }
        return estimateTab;
    }

    private ProjectWorkspacePage workspace() {
        if (workspace == null) {
            workspace = new ProjectWorkspacePage(page());
        }
        return workspace;
    }

    private WorkspaceTabs tabs() {
        if (tabs == null) {
            tabs = new WorkspaceTabs(page());
        }
        return tabs;
    }

    private ScheduleApiHelper scheduleApi() {
        if (scheduleApi == null) {
            scheduleApi = ScheduleApiHelper.create();
            scheduleApi.loginAdmin();
            world.registerTeardown(() -> {
                if (scheduleApi != null) {
                    scheduleApi.close();
                    scheduleApi = null;
                }
            });
        }
        return scheduleApi;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    private TestUser createUser(String key, String roleCode) {
        TestUser u = userFixture.createActiveUser(roleCode);
        users.put(key, u);
        world.registerTeardown(() -> userFixture.deleteUserAndResources(u));
        return u;
    }

    private long requireProject() {
        if (projectId == null) {
            throw new IllegalStateException("No project in scope; a project Given must run first.");
        }
        return projectId;
    }

    // ---- login (reused building block, mirrors CommonSteps) ----

    private void loginAdminUi() {
        var login = new com.foremen.qa.pages.LoginPage(page());
        login.open();
        login.login(com.foremen.qa.support.TestConfig.adminEmail(),
                com.foremen.qa.support.TestConfig.adminPassword());
        page().waitForURL(u -> !u.contains(com.foremen.qa.pages.LoginPage.ROUTE));
        new com.foremen.qa.pages.AppShell(page()).waitUntilRendered();
    }

    private void loginUserUi(TestUser user) {
        var login = new com.foremen.qa.pages.LoginPage(page());
        login.open();
        login.login(user.email(), user.password());
        page().waitForURL(u -> !u.contains(com.foremen.qa.pages.LoginPage.ROUTE));
    }

    // ================= Setup (Given) =================

    /**
     * A design-stage project with a dated anchor, a one-room estimate (so the schedule derives rows),
     * a manager + two ACTIVE workers, created via API. The estimate LINES are seeded in a later step
     * (through the browser, applying a package) because that is the real user path.
     */
    @Given("a scheduling project with a dated anchor and two active workers exists")
    public void aSchedulingProjectWithAnchorAndWorkers() {
        scheduleApi();
        ApiHelper api = adminApi();

        TestUser manager = createUser("MANAGER", "MANAGER");
        long managerRoleId = api.resolveRoleIdByCode("MANAGER");

        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", manager.id());
        m.put("projectRoleId", managerRoleId);
        members.add(m);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", DataGen.nextProjectName());
        body.put("startDate", "2026-11-02");
        body.put("endDate", "2026-11-27");
        body.put("members", members);
        long id = api.createProject(body);
        this.projectId = id;
        registerProjectTeardown(id);

        // One minimal room so applying a package yields estimate lines across categories.
        long roomTypeId = api.resolveFirstRowId("/api/room-types");
        Map<String, Object> roomBody = new LinkedHashMap<>();
        roomBody.put("projectId", id);
        roomBody.put("roomTypeId", roomTypeId);
        roomBody.put("label", "QA Room " + id);
        roomBody.put("ceilingHeight", 2.5);
        roomBody.put("floorArea", 10);
        roomBody.put("wallArea", 25);
        long roomId = api.createRoom(roomBody);
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteRoom(roomId);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        });

        // Two ACTIVE WORKER members → crewSize = 2.
        long workerRoleId = api.resolveRoleIdByCode("WORKER");
        createWorkerMember("WORKER1", id, workerRoleId);
        createWorkerMember("WORKER2", id, workerRoleId);
    }

    /** A design-stage project with members but NO estimate (empty schedule). */
    @Given("an empty scheduling project with no estimate exists")
    public void anEmptySchedulingProject() {
        scheduleApi();
        ApiHelper api = adminApi();
        TestUser manager = createUser("MANAGER_EMPTY", "MANAGER");
        long managerRoleId = api.resolveRoleIdByCode("MANAGER");
        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", manager.id());
        m.put("projectRoleId", managerRoleId);
        members.add(m);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", DataGen.nextProjectName());
        body.put("members", members);
        long id = api.createProject(body);
        this.projectId = id;
        registerProjectTeardown(id);
    }

    private void createWorkerMember(String key, long projectId, long workerRoleId) {
        TestUser worker = createUser(key, "WORKER");
        scheduleApi().loginAdmin();
        Resp assign = scheduleApi().assignWorker(worker.id(), projectId, workerRoleId);
        assertThat(assign.status())
                .as("assign worker %s (body %s)", key, assign.text())
                .isEqualTo(201);
    }

    private void registerProjectTeardown(long id) {
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(id);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        });
    }

    /**
     * Through the browser as ADMIN, apply the first offer package to the estimate and save, so the
     * schedule's rows are the real estimate work categories. Verified by reading the Schedule_API:
     * the view must then expose at least one row.
     */
    @Given("the project estimate has work lines from an applied package")
    public void theEstimateHasLinesFromAPackage() {
        loginAdminUi();
        EstimateTab tab = estimateTab();
        tab.open(requireProject()).waitUntilRendered();
        tab.selectFirstPackage();
        tab.clickApplyPackage();
        tab.clickSave();
        tab.waitUntilSaved();

        // Confirm (over the API) that the schedule now derives at least one row.
        scheduleApi().loginAdmin();
        page().waitForCondition(() -> scheduleApi().view(requireProject()).rows().size() > 0);
        Resp view = scheduleApi().view(requireProject());
        assertThat(view.rows().size())
                .as("after applying a package the schedule should derive rows from the estimate "
                        + "(view %s)", view.text())
                .isGreaterThan(0);
    }

    // ---- navigation ----

    @When("I open the schedule tab")
    public void iOpenTheScheduleTab() {
        // Settle on whichever terminal state renders (loaded / empty / error / read-only); the
        // specific assertion steps then check the state they expect.
        scheduleTab().open(requireProject()).waitUntilSettled();
    }

    @When("I open the project workspace")
    public void iOpenTheProjectWorkspace() {
        // Deep-link to the overview tab rather than the bare /projects/{id}: the bare route is
        // normalized to the user's CURRENT working project, which would navigate away from the
        // project under test. Deep-linking a tab pins the project.
        workspace().openTab(requireProject(), "overview").waitUntilRendered();
        tabs().waitUntilRendered();
    }

    /**
     * Open the schedule tab via the execution-stage design selector. A design-stage tab
     * ({@code scheduleDesign}) is not in the main strip once the project is in an execution status;
     * it is reached through the grouped design selector. Falls back to the main strip when the
     * selector is absent (the project is still design-stage).
     */
    @When("I open the schedule tab through the design selector")
    public void iOpenScheduleViaDesignSelector() {
        iOpenTheProjectWorkspace();
        WorkspaceTabs t = tabs();
        if (t.isDesignSelectorVisible()) {
            t.openDesignSelector();
            t.clickDesignTab(ScheduleTab.TAB_KEY);
        } else if (t.isTabVisible(ScheduleTab.TAB_KEY)) {
            t.clickTab(ScheduleTab.TAB_KEY);
        } else {
            scheduleTab().open(requireProject());
        }
        scheduleTab().waitUntilSettled();
    }

    // ================= Tab visibility / routing (R14) =================

    @Then("the schedule tab is visible in the tab strip")
    public void theScheduleTabVisibleInStrip() {
        assertThat(tabs().isTabVisible(ScheduleTab.TAB_KEY))
                .as("the scheduleDesign tab should be present in the strip").isTrue();
    }

    @When("I click the schedule tab in the strip")
    public void iClickTheScheduleTabInStrip() {
        tabs().clickTab(ScheduleTab.TAB_KEY);
        scheduleTab().waitUntilSettled();
    }

    @Then("the schedule workspace URL ends with {string}")
    public void theWorkspaceUrlEndsWith(String suffix) {
        page().waitForURL(u -> u.endsWith(suffix));
        assertThat(page().url()).as("workspace URL").endsWith(suffix);
    }

    @Then("the schedule tab is rendered")
    public void theScheduleTabIsRendered() {
        assertThat(scheduleTab().isRendered()).as("the schedule tab should render").isTrue();
    }

    @Then("the schedule tab shows no raw i18n keys")
    public void theScheduleTabNoRawKeys() {
        String text = scheduleTab().tabText();
        assertThat(text).as("no raw projectSchedule.* keys should be visible")
                .doesNotContain("projectSchedule.");
    }

    // ================= View (R15) =================

    @Then("the schedule shows one row per estimate category")
    public void theScheduleShowsRows() {
        assertThat(scheduleTab().rowLabelCount())
                .as("the schedule should show at least one category row").isGreaterThan(0);
    }

    @Then("each schedule row shows its line count and planned duration without man-days or rate")
    public void eachRowShowsVolumeWithoutManDays() {
        // Read the row ids off the API and check the label text per row.
        scheduleApi().loginAdmin();
        List<Long> ids = scheduleApi().view(requireProject()).rowCategoryIds();
        assertThat(ids).as("schedule rows").isNotEmpty();
        for (Long id : ids) {
            Locator lines = scheduleTab().rowLines(id);
            assertThat(lines.count()).as("row %s line-count cell", id).isGreaterThan(0);
        }
        // No man-days / rate token anywhere in the tab text.
        String text = scheduleTab().tabText().toLowerCase();
        assertThat(text).as("no man-days / rate in the schedule tab")
                .doesNotContain("man-day").doesNotContain("manday")
                .doesNotContain("dailyoutput");
    }

    @Then("the week view header is shown with the calendar legend")
    public void theWeekViewAndLegend() {
        assertThat(scheduleTab().isWeekViewActive()).as("week view active by default").isTrue();
        assertThat(scheduleTab().isWeekHeaderShown()).as("week header shown").isTrue();
        assertThat(scheduleTab().isLegendShown()).as("calendar legend shown").isTrue();
    }

    @Then("weekend cells are shaded")
    public void weekendCellsShaded() {
        assertThat(scheduleTab().weekendCellCount())
                .as("the anchored timeline should shade weekend days").isGreaterThan(0);
    }

    @When("I switch the schedule to day view")
    public void iSwitchToDayView() {
        scheduleTab().switchToDayView();
    }

    @Then("the day view header is shown")
    public void theDayViewHeaderShown() {
        assertThat(scheduleTab().isDayViewActive()).as("day view active").isTrue();
        assertThat(scheduleTab().isDayHeaderShown()).as("day header shown").isTrue();
    }

    @Then("the schedule money columns are shown")
    public void theMoneyColumnsShown() {
        assertThat(scheduleTab().rowValueCount())
                .as("a Money_Viewer should see per-row category values").isGreaterThan(0);
    }

    @Then("the schedule money columns are hidden")
    public void theMoneyColumnsHidden() {
        assertThat(scheduleTab().rowValueCount())
                .as("a non-Money_Viewer should not see per-row category values").isZero();
    }

    @Then("the schedule shows no editing actions")
    public void theScheduleShowsNoEditing() {
        assertThat(scheduleTab().editableBarCount())
                .as("a read-only viewer should see no interactive bars").isZero();
        assertThat(scheduleTab().saveButton().count())
                .as("a read-only viewer should see no Save action").isZero();
    }

    @Then("the schedule summary shows the active crew size")
    public void theSummaryShowsCrew() {
        assertThat(scheduleTab().crewText())
                .as("summary crew line should carry the active worker count").isNotBlank();
    }

    @Then("the empty estimate state is shown")
    public void theEmptyStateShown() {
        scheduleTab().waitUntilSettled();
        assertThat(scheduleTab().isEmptyShown())
                .as("a project with no estimate rows should show the empty state").isTrue();
    }

    // ================= Editing (R16) =================

    @When("I auto-create the schedule from the UI")
    public void iAutoCreateFromUi() {
        ScheduleTab tab = scheduleTab();
        tab.clickAutoCreate();
        tab.autoCreateDialog().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // The dialog must explain in prose and never show a rate input.
        assertThat(tab.autoCreateFormula().count()).as("auto-create prose formula").isGreaterThan(0);
        assertThat(tab.autoCreateCrew().count()).as("auto-create crew line").isGreaterThan(0);
        tab.autoCreateConfirm().first().click();
        tab.autoCreateDialog().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        tab.waitUntilRendered();
    }

    @Then("the schedule has bars for every row")
    public void theScheduleHasBarsForEveryRow() {
        scheduleApi().loginAdmin();
        Resp readiness = scheduleApi().readiness(requireProject());
        assertThat(readiness.string("state"))
                .as("after auto-create readiness should be DONE (body %s)", readiness.text())
                .isEqualTo("DONE");
    }

    @When("I move the first scheduled bar with the keyboard")
    public void iMoveFirstBarWithKeyboard() {
        Locator bar = page().locator("[data-testid^='schedule-bar-editable-']").first();
        bar.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        bar.focus();
        page().keyboard().press("ArrowRight");
    }

    @Then("the schedule has unsaved changes")
    public void theScheduleHasUnsavedChanges() {
        assertThat(scheduleTab().isUnsavedBadgeShown())
                .as("an edit should surface the unsaved badge").isTrue();
        assertThat(scheduleTab().changedRowCount())
                .as("an edited row should carry the changed mark").isGreaterThan(0);
        assertThat(scheduleTab().isSaveEnabled()).as("Save should be enabled while dirty").isTrue();
    }

    @When("I save the schedule")
    public void iSaveTheSchedule() {
        scheduleTab().clickSave();
        // The save resets the draft: the unsaved badge disappears.
        page().waitForCondition(() -> scheduleTab().unsavedBadge().count() == 0);
    }

    @Then("the schedule has no unsaved changes")
    public void theScheduleHasNoUnsavedChanges() {
        assertThat(scheduleTab().isUnsavedBadgeShown())
                .as("after save the unsaved badge should be gone").isFalse();
    }

    @When("I discard the schedule changes")
    public void iDiscardChanges() {
        scheduleTab().clickDiscard();
        page().waitForCondition(() -> scheduleTab().unsavedBadge().count() == 0);
    }

    @When("I open the bar edit dialog from the keyboard")
    public void iOpenBarEditDialogKeyboard() {
        Locator bar = page().locator("[data-testid^='schedule-bar-editable-']").first();
        bar.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        bar.focus();
        page().keyboard().press("Enter");
        scheduleTab().barEditDialog().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    @Then("the bar edit dialog is shown")
    public void theBarEditDialogShown() {
        assertThat(scheduleTab().isBarEditDialogShown()).as("bar edit dialog shown").isTrue();
    }

    @When("I enter an invalid duration of {int} in the bar dialog")
    public void iEnterInvalidDuration(int value) {
        ScheduleTab tab = scheduleTab();
        Locator dur = tab.barEditDuration();
        dur.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        dur.first().fill(String.valueOf(value));
    }

    @Then("the bar dialog shows a validation error and apply is blocked")
    public void theBarDialogValidationError() {
        ScheduleTab tab = scheduleTab();
        boolean error = tab.barEditDurationError().count() > 0 || tab.barEditTooLongError().count() > 0;
        assertThat(error).as("an invalid duration should surface a field error").isTrue();
        assertThat(tab.barEditApply().first().isEnabled())
                .as("apply should be disabled while the dialog is invalid").isFalse();
    }

    @When("I close the bar edit dialog")
    public void iCloseBarEditDialog() {
        scheduleTab().barEditCancel().first().click();
        scheduleTab().barEditDialog().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    @When("I clear the first scheduled row")
    public void iClearFirstScheduledRow() {
        scheduleApi().loginAdmin();
        List<Long> ids = scheduleApi().view(requireProject()).rowCategoryIds();
        Long target = null;
        for (Long id : ids) {
            if (scheduleTab().rowClearAction(id).count() > 0) {
                target = id;
                break;
            }
        }
        assertThat(target).as("a scheduled row with a clear action").isNotNull();
        scheduleTab().rowClearAction(target).first().click();
        this.clearedCategoryId = target;
    }

    private Long clearedCategoryId;

    @Then("that row becomes unscheduled in the draft")
    public void thatRowBecomesUnscheduled() {
        assertThat(clearedCategoryId).as("a cleared row id").isNotNull();
        page().waitForCondition(() -> scheduleTab().isRowUnscheduled(clearedCategoryId));
        assertThat(scheduleTab().isRowUnscheduled(clearedCategoryId))
                .as("the cleared row shows the unscheduled hint").isTrue();
    }

    // ================= Auto-create disabled when crew is 0 =================

    @When("I deactivate both workers")
    public void iDeactivateBothWorkers() {
        ScheduleApiHelper api = scheduleApi();
        api.loginAdmin();
        TestUser w1 = users.get("WORKER1");
        TestUser w2 = users.get("WORKER2");
        api.patchAssignmentStatus(w1.id(), requireProject(), "INACTIVE");
        api.patchAssignmentStatus(w2.id(), requireProject(), "INACTIVE");
    }

    @Then("the auto-create action is disabled")
    public void theAutoCreateDisabled() {
        scheduleTab().waitUntilRendered();
        assertThat(scheduleTab().autoCreateDisabledButton().count())
                .as("auto-create should be disabled with a 0 crew").isGreaterThan(0);
    }

    // ================= Readiness chip (R12) =================

    @Then("the readiness widget shows a schedule gate chip")
    public void theReadinessShowsScheduleChip() {
        Locator widget = workspace().readinessWidget();
        widget.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // The schedule chip appears once the readiness query resolves; poll the widget text. RU gate
        // label is "График", PL is "Harmonogram"; the chip composes "<gate>: <state>".
        page().waitForCondition(() -> {
            String t = widget.first().innerText();
            return t != null && (t.contains("График") || t.contains("Harmonogram"));
        });
        String text = widget.first().innerText();
        assertThat(text).as("readiness widget text %s", text)
                .containsAnyOf("График", "Harmonogram");
    }

    // Note: "the readiness widget is shown" is defined in WorkspaceSteps and reused here.

    // ================= Read-only via API-set status =================

    @Given("the project is moved to an active status")
    public void theProjectMovedToActive() {
        // PUT /api/projects/{id} with the current name + ACTIVE (generic update requires name).
        ApiHelper api = adminApi();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "QA Gantt Locked " + requireProject());
        body.put("status", "ACTIVE");
        var ctx = com.microsoft.playwright.Playwright.create();
        try {
            var req = ctx.request().newContext(
                    new com.microsoft.playwright.APIRequest.NewContextOptions()
                            .setBaseURL(com.foremen.qa.support.TestConfig.apiUrl()));
            var resp = req.put("/api/projects/" + requireProject(),
                    com.microsoft.playwright.options.RequestOptions.create()
                            .setHeader("Authorization", "Bearer " + api.accessToken())
                            .setData(body));
            assertThat(resp.status())
                    .as("move project to ACTIVE (body %s)", safeText(resp))
                    .isBetween(200, 299);
            req.dispose();
        } finally {
            ctx.close();
        }
    }

    private static String safeText(com.microsoft.playwright.APIResponse resp) {
        try {
            return resp.text();
        } catch (RuntimeException e) {
            return "<no body>";
        }
    }

    @Then("the schedule read-only banner is shown")
    public void theReadOnlyBannerShown() {
        scheduleTab().waitUntilRendered();
        assertThat(scheduleTab().isReadOnlyBannerShown())
                .as("a locked project should render the read-only banner").isTrue();
    }

    // ================= Membership for a provisioned role user =================

    /**
     * Add the current provisioned test user (created via {@code TestUserSteps}) to the scheduling
     * project under their role, so a non-ADMIN role (e.g. FOREMAN) can access the schedule (a
     * non-member gets 404). Resolves the project-role id from the user's role code.
     */
    @Given("the test user is a member of the scheduling project")
    public void theTestUserIsAMemberOfTheProject() {
        TestUser user = world.currentTestUser();
        if (user == null) {
            throw new IllegalStateException("No current test user; create one with a role Given first.");
        }
        ApiHelper api = adminApi();
        long roleId = api.resolveRoleIdByCode(user.roleCode());
        api.assignProjectMember(user.id(), requireProject(), roleId);
    }

    // ================= Session reset =================

    /**
     * Clear the browser auth session so a subsequent UI login starts from a clean, unauthenticated
     * state. Needed because the estimate-seeding step logs the browser in as ADMIN; navigating to
     * {@code /login} while already authenticated bounces back to the app and the login form never
     * appears, so a role switch must first sign out.
     */
    @When("I sign out of the browser")
    public void iSignOutOfTheBrowser() {
        page().navigate(com.foremen.qa.support.TestConfig.frontendUrl() + "/");
        page().evaluate("() => { localStorage.clear(); sessionStorage.clear(); }");
    }
}
