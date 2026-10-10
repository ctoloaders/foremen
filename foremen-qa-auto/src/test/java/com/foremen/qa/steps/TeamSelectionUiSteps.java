package com.foremen.qa.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.pages.ProjectWorkspacePage;
import com.foremen.qa.pages.ProjectsPage;
import com.foremen.qa.pages.TeamTab;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TeamApiHelper;
import com.foremen.qa.support.TeamApiHelper.Resp;
import com.foremen.qa.support.World;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-09 Part B (browser UI) steps for the FOR-05-09 Team tab.
 *
 * <p>Seeds the project / members / uncategorized workers via the Team API (fast, deterministic),
 * then drives the real browser Team tab and asserts the rendered blocks, counts, warnings, the
 * worker-type assign flow, the tag filter, the readiness chip, i18n, and the create-form hint.
 * Repeatability: {@link DataGen} run-id + LIFO teardown in {@link World}.
 */
public class TeamSelectionUiSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();

    private TeamApiHelper team;
    private TeamTab teamTab;
    private ProjectWorkspacePage workspace;

    private Long projectId;
    private Long uncategorizedWorkerUserId;
    private final Map<String, TestUser> users = new LinkedHashMap<>();

    public TeamSelectionUiSteps(World world) {
        this.world = world;
    }

    private Page page() {
        return Hooks.currentPage();
    }

    private TeamTab teamTab() {
        if (teamTab == null) {
            teamTab = new TeamTab(page());
        }
        return teamTab;
    }

    private ProjectWorkspacePage workspace() {
        if (workspace == null) {
            workspace = new ProjectWorkspacePage(page());
        }
        return workspace;
    }

    private TeamApiHelper team() {
        if (team == null) {
            team = TeamApiHelper.create();
            team.loginAdmin();
            world.registerTeardown(() -> {
                if (team != null) {
                    team.close();
                    team = null;
                }
            });
        }
        return team;
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

    // ---- Fixtures (seeded via API) ----

    @Given("a team project with a manager and a client exists for the UI")
    public void aTeamProjectForUi() {
        team();
        ApiHelper api = adminApi();
        TestUser manager = createUser("MANAGER", "MANAGER");
        TestUser client = createUser("CLIENT", "CLIENT");

        long managerRoleId = api.resolveRoleIdByCode("MANAGER");
        long clientRoleId = api.resolveRoleIdByCode("CLIENT");
        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", manager.id());
        m.put("projectRoleId", managerRoleId);
        members.add(m);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("userId", client.id());
        c.put("projectRoleId", clientRoleId);
        members.add(c);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", DataGen.nextProjectName());
        body.put("members", members);
        long id = api.createProject(body);
        this.projectId = id;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(id);
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        });
    }

    @Given("the project has an uncategorized worker")
    public void theProjectHasAnUncategorizedWorker() {
        TestUser worker = createUser("WORKER", "WORKER");
        // Assign the worker without a worker type → Uncategorized_Worker.
        Resp assign = team().assign(worker.id(), requireProject(), null, null, List.of("vip"));
        assertThat(assign.status()).as("assign uncategorized worker (body %s)", assign.text())
                .isEqualTo(201);
        this.uncategorizedWorkerUserId = worker.id();
    }

    // ---- Navigation ----

    @When("I open the team tab")
    public void iOpenTheTeamTab() {
        teamTab().open(requireProject()).waitUntilRendered();
    }

    // ---- Assertions: tab + routing ----

    @Then("the team tab is rendered")
    public void theTeamTabIsRendered() {
        assertThat(teamTab().isRendered()).as("the team tab should render").isTrue();
    }

    @Then("the workspace URL ends with {string}")
    public void theWorkspaceUrlEndsWith(String suffix) {
        assertThat(page().url()).as("workspace URL").endsWith(suffix);
    }

    // ---- Assertions: three blocks ----

    @Then("the three team blocks are rendered in order")
    public void theThreeBlocksAreRendered() {
        assertThat(teamTab().isBlockVisible("ADMIN_STAFF")).as("ADMIN_STAFF block").isTrue();
        assertThat(teamTab().isBlockVisible("WORKERS")).as("WORKERS block").isTrue();
        assertThat(teamTab().isBlockVisible("CLIENTS")).as("CLIENTS block").isTrue();
        // Verify DOM order ADMIN_STAFF -> WORKERS -> CLIENTS via bounding boxes.
        double admin = blockTop("ADMIN_STAFF");
        double workers = blockTop("WORKERS");
        double clients = blockTop("CLIENTS");
        assertThat(admin).as("ADMIN_STAFF above WORKERS").isLessThan(workers);
        assertThat(workers).as("WORKERS above CLIENTS").isLessThan(clients);
    }

    private double blockTop(String block) {
        Locator b = teamTab().block(block).first();
        return b.boundingBox() != null ? b.boundingBox().y : -1;
    }

    @Then("the {string} block count is {int}")
    public void theBlockCountIs(String block, int expected) {
        assertThat(teamTab().countValue(block)).as("%s count", block).isEqualTo(expected);
    }

    @Then("the {string} block count is at least {int}")
    public void theBlockCountIsAtLeast(String block, int expected) {
        assertThat(teamTab().countValue(block)).as("%s count", block)
                .isGreaterThanOrEqualTo(expected);
    }

    // ---- Assertions: foreman hint ----

    @Then("the foreman readiness hint is shown")
    public void theForemanHintIsShown() {
        assertThat(teamTab().isForemanHintShown()).as("ADMIN_STAFF foreman hint").isTrue();
    }

    // ---- Assertions: worker type missing ----

    @Then("the worker-type-missing badge is shown")
    public void theWorkerTypeMissingBadgeShown() {
        assertThat(teamTab().workerTypeMissingBadgeCount())
                .as("worker-type-missing badge count").isGreaterThan(0);
    }

    @Then("the block-level missing worker-type warning is shown")
    public void theBlockWarningShown() {
        assertThat(teamTab().missingWorkerTypeWarningCount())
                .as("block-level missing-type warning").isGreaterThan(0);
    }

    @When("I assign a worker type to the uncategorized worker through the UI")
    public void iAssignWorkerTypeViaUi() {
        long memberId = resolveMemberId(uncategorizedWorkerUserId);
        TeamTab tab = teamTab();
        tab.openMemberMenu(memberId);
        Locator action = tab.memberActionWorkerType(memberId);
        action.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        action.first().click();
        // The assign/change worker-type dialog.
        Locator dialog = page().locator("[data-testid=change-worker-type-dialog]");
        dialog.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        Locator select = page().locator("[data-testid=worker-type-change-select]");
        select.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // For an uncategorized worker the first <option> is a disabled empty placeholder; select a
        // REAL active worker type by its numeric value so the confirm button enables.
        long workerTypeId = team().resolveWorkerTypeId("BASE");
        select.first().selectOption(String.valueOf(workerTypeId));
        Locator confirm = page().locator("[data-testid=change-worker-type-confirm]");
        confirm.first().click();
        dialog.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        teamTab().waitUntilRendered();
    }

    @Then("the worker-type-missing badge is no longer shown")
    public void theWorkerTypeMissingBadgeGone() {
        // After a successful assignment the uncategorized count drops; with one worker it reaches 0.
        page().waitForFunction(
                "() => document.querySelectorAll('[data-testid=worker-type-missing]').length === 0",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));
        assertThat(teamTab().workerTypeMissingBadgeCount())
                .as("worker-type-missing badge after assignment").isZero();
    }

    /** Resolve the project_members membership id for a userId via the Team API list. */
    private long resolveMemberId(long userId) {
        Resp list = team().listMembers(requireProject());
        JsonObject member = list.memberByUserId(userId);
        assertThat(member).as("member for userId %s in %s", userId, list.text()).isNotNull();
        return member.get("id").getAsLong();
    }

    // ---- Assertions: tag filter ----

    @Then("the tag filter is shown")
    public void theTagFilterShown() {
        assertThat(teamTab().isTagFilterShown()).as("tag filter control").isTrue();
    }

    @When("I filter the team by tag {string}")
    public void iFilterByTag(String tag) {
        teamTab().selectTag(tag);
    }

    @Then("the {string} block shows no matching members")
    public void theBlockShowsNoMatch(String block) {
        assertThat(teamTab().isNoMatchShown(block)).as("%s no-match state", block).isTrue();
    }

    // ---- Assertions: readiness chip ----
    // Note: "the readiness widget is shown" is already defined in WorkspaceSteps and reused here.

    @Then("the readiness widget shows a team gate chip")
    public void theReadinessWidgetShowsTeamChip() {
        Locator widget = workspace().readinessWidget();
        widget.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        String text = widget.first().innerText();
        // RU gate label is "Команда", PL is "Zespół"; the chip composes "<gate>: <state>".
        assertThat(text).as("readiness widget text %s", text)
                .containsAnyOf("Команда", "Zespół");
    }

    // ---- Assertions: i18n ----

    @Then("the team tab shows no raw i18n keys")
    public void theTeamTabNoRawKeys() {
        String text = teamTab().tabText();
        assertThat(text).as("no raw team.* keys should be visible")
                .doesNotContain("team.block.").doesNotContain("team.column.")
                .doesNotContain("team.badge.").doesNotContain("workspace.tab.");
    }

    // ---- Create-form admin-staff hint ----

    @When("I open the project create form")
    public void iOpenProjectCreateForm() {
        new ProjectsPage(page()).open().openCreate();
    }

    @Then("the create form shows the team workers hint")
    public void theCreateFormShowsWorkersHint() {
        Locator hint = page().locator("[data-testid=team-workers-hint]");
        hint.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        assertThat(hint.count()).as("team-workers-hint present").isGreaterThan(0);
        String text = hint.first().innerText();
        assertThat(text).as("hint is localized, not a raw key")
                .doesNotContain("projects.form.team.workersHint");
    }
}
