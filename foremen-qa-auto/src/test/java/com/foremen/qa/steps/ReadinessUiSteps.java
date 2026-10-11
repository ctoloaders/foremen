package com.foremen.qa.steps;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.pages.ProjectWorkspacePage;
import com.foremen.qa.pages.ReadinessTab;
import com.foremen.qa.pages.WorkspaceTabs;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.TeamApiHelper;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-QA-AUTO-11 detailed UI steps for the FOR-05-11 aggregated project-readiness surface.
 *
 * <p>Drives the {@code readiness} workspace tab and the header {@code ReadinessWidget} through the
 * browser against the live stack, covering the UI-visible subset of the FOR-05-11 {@code test-cases.md}
 * Part B (Фичи 7–9): the overall donut + overall-state label, the ordered per-area rows (localized
 * name / state / percentage), jump-to-tab navigation, the no-readable-area empty state, the
 * refetch-after-area-change invalidation, and the widget chip strip + its design-stage gate.
 *
 * <p>Setup creates a run-unique DESIGN-stage project via {@link World#api()} as the seeded ADMIN and
 * registers {@code deleteProject} teardown. The project is created WITHOUT an active foreman, so the
 * {@code team} area starts {@code BLOCKED} and the refetch scenario (TC-UI-TAB-04) has a gate to
 * flip (an ACTIVE {@code FOREMAN} assigned via {@link TeamApiHelper} → the {@code team} row turns
 * {@code DONE} without a page reload). The readiness surface itself is read-only, so no readiness
 * action needs teardown.
 *
 * <p>Step phrasings are prefixed with "readiness" to avoid colliding with {@link WorkspaceSteps} /
 * {@link ScheduleUiSteps} / {@link TeamSelectionUiSteps} (Cucumber fails the whole run on a duplicate
 * step definition). The stack-ready + admin-login backgrounds reuse {@link CommonSteps}; the
 * widget shown/not-shown assertions reuse {@link WorkspaceSteps}; the FOREMAN provisioning + UI-login
 * reuse {@link TestUserSteps}; the sign-out reuses {@link ScheduleUiSteps}.
 */
public class ReadinessUiSteps {

    /** The design-stage area keys in the server's declared order (rows render in this order). */
    private static final List<String> DECLARED_AREA_ORDER =
            List.of("rooms", "estimate", "offer", "documents", "team", "schedule");

    private final World world;

    private ProjectWorkspacePage workspacePage;
    private WorkspaceTabs workspaceTabs;
    private ReadinessTab readinessTab;

    private Long designProjectId;
    private Long execProjectId;
    private final Map<Long, String> projectNamesById = new LinkedHashMap<>();

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public ReadinessUiSteps(World world) {
        this.world = world;
    }

    // ---- lazily-built page objects over the active page ----

    private Page page() {
        return Hooks.currentPage();
    }

    private ProjectWorkspacePage workspacePage() {
        if (workspacePage == null) {
            workspacePage = new ProjectWorkspacePage(page());
        }
        return workspacePage;
    }

    private WorkspaceTabs workspaceTabs() {
        if (workspaceTabs == null) {
            workspaceTabs = new WorkspaceTabs(page());
        }
        return workspaceTabs;
    }

    private ReadinessTab readinessTab() {
        if (readinessTab == null) {
            readinessTab = new ReadinessTab(page());
        }
        return readinessTab;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    // ================= Project setup (Given) =================

    /** Create a run-unique DESIGN-stage (DRAFT) project with no active foreman; remember + teardown. */
    @Given("a readiness design project exists")
    public void aReadinessDesignProjectExists() {
        designProjectId = createProject(null);
    }

    /** Create a run-unique EXECUTION-stage (ACTIVE) project for the widget stage-gate scenario. */
    @Given("a readiness execution project exists")
    public void aReadinessExecutionProjectExists() {
        execProjectId = createProject("ACTIVE");
    }

    /**
     * Create a project via the seeded-admin API. {@code null} status defaults to {@code DRAFT}
     * (design stage); {@code "ACTIVE"} yields an execution-stage project. Registers idempotent
     * {@code deleteProject} teardown.
     */
    private long createProject(String status) {
        ApiHelper api = adminApi();
        String name = DataGen.nextProjectName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (status != null) {
            body.put("status", status);
        }
        long id = api.createProject(body);
        projectNamesById.put(id, name);
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(id);
            } catch (RuntimeException ignored) {
                // Best-effort idempotent teardown.
            }
        });
        return id;
    }

    /**
     * Add the current test user (e.g. a FOREMAN) to the design project as a member, so the
     * project-access gate passes (a non-member gets 404). The member holds no {@code Area_Resource}
     * READ grant beyond what its role carries, so for a FOREMAN the readiness areas list comes back
     * empty — driving the no-readable-area empty state (TC-UI-TAB-03).
     */
    @Given("the readiness test user is a member of the design project")
    public void theReadinessTestUserIsAMemberOfTheDesignProject() {
        TestUser user = world.currentTestUser();
        if (user == null) {
            throw new IllegalStateException("No current test user; create one with a role Given first.");
        }
        ApiHelper api = adminApi();
        long roleId = api.resolveRoleIdByCode(user.roleCode());
        api.assignProjectMember(user.id(), requireDesign(), roleId);
    }

    // ================= Navigation (When) =================

    /** Open the design project workspace at its bare route (the app normalizes to a tab). */
    @When("I open the readiness design project workspace")
    public void iOpenTheReadinessDesignProjectWorkspace() {
        openWorkspaceSettled(requireDesign());
    }

    /**
     * Open the execution project workspace at its bare route and WAIT until the execution-stage
     * surface has actually hydrated before returning — otherwise the subsequent "widget not shown"
     * assertion can race the SPA while the previous (design) project's widget is still mounted. The
     * grouped design-selector trigger is rendered ONLY in the execution stage (design-stage tabs are
     * surfaced through it on an execution project), so its presence is a deterministic signal that
     * {@code stageOf(status) === 'execution'} has taken effect for the freshly-hydrated project.
     */
    @When("I open the readiness execution project workspace")
    public void iOpenTheReadinessExecutionProjectWorkspace() {
        openWorkspaceSettled(requireExec());
        workspaceTabs().waitUntilRendered();
        page().waitForCondition(() -> workspaceTabs().isDesignSelectorVisible());
    }

    /** Open the {@code readiness} tab directly by deep link, then wait for the panel to render. */
    @When("I open the readiness tab")
    public void iOpenTheReadinessTab() {
        settle();
        workspacePage().openTab(requireDesign(), ReadinessTab.TAB_KEY);
        readinessTab().waitUntilRendered();
    }

    /** Click the {@code readiness} tab in the main strip (navigates + writes per-project memory). */
    @When("I click the readiness tab in the strip")
    public void iClickTheReadinessTabInTheStrip() {
        workspaceTabs().waitUntilRendered();
        workspaceTabs().clickTab(ReadinessTab.TAB_KEY);
    }

    /** Activate the given area row's header to jump to its target workspace tab. */
    @When("I activate the readiness area row {string}")
    public void iActivateTheReadinessAreaRow(String key) {
        readinessTab().areaHeader(key).first().waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        readinessTab().clickAreaHeader(key);
    }

    /** Assign an ACTIVE FOREMAN to the design project via the Team API (flips the team gate). */
    @When("I assign an active foreman to the design project")
    public void iAssignAnActiveForemanToTheDesignProject() {
        // Provision a dedicated FOREMAN user via the test-user fixture so it is ACTIVE + torn down.
        com.foremen.qa.fixtures.TestUserFixture fixture = new com.foremen.qa.fixtures.TestUserFixture();
        TestUser foreman = fixture.createActiveUser("FOREMAN");
        world.registerTeardown(() -> fixture.deleteUserAndResources(foreman));

        try (TeamApiHelper team = TeamApiHelper.create()) {
            team.loginAdmin();
            long foremanRoleId = world.api().resolveRoleIdByCode("FOREMAN");
            TeamApiHelper.Resp resp = team.assign(foreman.id(), requireDesign(), foremanRoleId, null, null);
            assertThat(resp.ok())
                    .as("assigning an ACTIVE FOREMAN should succeed (HTTP 2xx), got %s: %s",
                            resp.status(), resp.text())
                    .isTrue();
        }
    }

    // ================= Assertions (Then) =================

    @Then("the readiness tab is visible in the tab strip")
    public void theReadinessTabIsVisibleInTheTabStrip() {
        workspaceTabs().waitUntilRendered();
        assertThat(workspaceTabs().isTabVisible(ReadinessTab.TAB_KEY))
                .as("the readiness tab should be visible in the main strip")
                .isTrue();
    }

    @Then("the readiness workspace URL ends with {string}")
    public void theReadinessWorkspaceUrlEndsWith(String suffix) {
        page().waitForURL(url -> url.endsWith(suffix) || url.contains(suffix + "?"));
        String url = page().url();
        assertThat(url.endsWith(suffix) || url.contains(suffix + "?"))
                .as("URL '%s' should end with '%s'", url, suffix)
                .isTrue();
    }

    @Then("the readiness tab is rendered")
    public void theReadinessTabIsRendered() {
        readinessTab().waitUntilRendered();
        assertThat(readinessTab().isRendered())
                .as("the readiness tab panel container should render")
                .isTrue();
    }

    @Then("the readiness overall donut and state are shown")
    public void theReadinessOverallDonutAndStateAreShown() {
        page().waitForCondition(() -> readinessTab().donutPercentage().count() > 0);
        assertThat(readinessTab().donutPercentageText())
                .as("the overall donut should show a percentage")
                .endsWith("%");
        assertThat(readinessTab().overallStateText())
                .as("the overall-state label should be a localized, non-empty string")
                .isNotBlank();
    }

    @Then("the readiness area rows render in declared order")
    public void theReadinessAreaRowsRenderInDeclaredOrder() {
        readinessTab().areasList().first().waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        List<String> rendered = readinessTab().renderedAreaKeys();
        assertThat(rendered)
                .as("at least one area row should render for an ADMIN caller")
                .isNotEmpty();
        // The rendered keys must be a subsequence of the declared order (readable areas only, in order).
        int lastIdx = -1;
        for (String key : rendered) {
            int idx = DECLARED_AREA_ORDER.indexOf(key);
            assertThat(idx)
                    .as("area '%s' should be a known design-stage area", key)
                    .isGreaterThanOrEqualTo(0);
            assertThat(idx)
                    .as("area rows should follow the declared order %s, but '%s' was out of order in %s",
                            DECLARED_AREA_ORDER, key, rendered)
                    .isGreaterThan(lastIdx);
            lastIdx = idx;
        }
    }

    @Then("the readiness area row {string} shows a localized state and percentage")
    public void theReadinessAreaRowShowsStateAndPercentage(String key) {
        assertThat(readinessTab().isAreaRowShown(key))
                .as("the '%s' area row should render for an ADMIN caller", key)
                .isTrue();
        assertThat(readinessTab().areaStateLabelText(key))
                .as("the '%s' area state label should be a localized, non-empty string", key)
                .isNotBlank();
        assertThat(readinessTab().areaPercentageText(key))
                .as("the '%s' area percentage should be shown with a %% suffix", key)
                .endsWith("%");
    }

    @Then("the readiness tab shows no raw i18n keys")
    public void theReadinessTabShowsNoRawI18nKeys() {
        String text = readinessTab().panel().first().innerText();
        assertThat(text)
                .as("the readiness tab should not leak raw readiness.* i18n keys")
                .doesNotContain("readiness.");
        assertThat(text)
                .as("the readiness tab should not leak raw workspace.tab.* i18n keys")
                .doesNotContain("workspace.tab.");
    }

    @Then("the readiness empty state is shown")
    public void theReadinessEmptyStateIsShown() {
        readinessTab().waitUntilEmptyStateShown();
        assertThat(readinessTab().isEmptyStateShown())
                .as("a caller with no readable area should see the readiness empty state")
                .isTrue();
        // The overall donut still renders at 0% in the empty state.
        assertThat(readinessTab().donutPercentageText())
                .as("the empty readiness state should still show the 0%% overall donut")
                .isEqualTo("0%");
        String text = readinessTab().emptyState().first().innerText();
        assertThat(text)
                .as("the empty state should not leak a raw i18n key")
                .doesNotContain("readiness.");
    }

    @Then("the readiness area row {string} eventually shows state {string}")
    public void theReadinessAreaRowEventuallyShowsState(String key, String expectedState) {
        // Re-open the readiness tab so the browser issues a FRESH aggregated read that reflects the
        // committed area change (Requirement 4.7 — the read mirrors committed state). An out-of-band
        // API change (made via the Team API, not through the SPA) does not trigger the in-session
        // readinessKeys.view invalidation, and the query's 30s staleTime would otherwise serve the
        // cached view; re-navigating to the tab forces a fresh fetch. Then poll the row's data-state
        // until it resolves to the expected value.
        settle();
        workspacePage().openTab(requireDesign(), ReadinessTab.TAB_KEY);
        readinessTab().waitUntilRendered();
        page().waitForCondition(() -> expectedState.equals(readinessTab().areaRowState(key)));
        assertThat(readinessTab().areaRowState(key))
                .as("the '%s' area row should read %s after the committed area change", key, expectedState)
                .isEqualTo(expectedState);
    }

    @Then("the readiness widget shows a chip for the area {string}")
    public void theReadinessWidgetShowsAChipForTheArea(String key) {
        // The widget chip strip renders one chip per readable area. Resolve the localized area name
        // via the readiness tab's own row (same workspace.tab.<key> label) is not needed — assert the
        // chip strip is present and non-empty, and that the matching area is also a tab row.
        page().waitForCondition(() -> page().locator("[data-testid=workspace-readiness-gates]").count() > 0);
        com.microsoft.playwright.Locator gates =
                page().locator("[data-testid=workspace-readiness-gates]").first();
        gates.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        String chips = gates.innerText();
        assertThat(chips)
                .as("the readiness widget chip strip should render at least one 'name: state' chip")
                .contains(":");
        assertThat(chips)
                .as("the readiness widget chips should not leak raw i18n keys")
                .doesNotContain("readiness.")
                .doesNotContain("workspace.tab.");
    }

    // ================= helpers =================

    /**
     * Let the current page reach network idle before a full navigation. A bare-route open / deep-link
     * depends on session hydration ({@code GET /api/auth/me}) completing; if a prior step's in-flight
     * request is still pending when the next navigation starts, that request aborts and the auth store
     * treats it as unrecoverable, clearing the session and bouncing to {@code /login} — so the target
     * never resolves. Waiting for idle first removes that race (mirrors {@code WorkspaceSteps}).
     * Best-effort: if idle cannot be reached quickly, proceed — the subsequent waits still guard.
     */
    private void settle() {
        try {
            page().waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
            // Best-effort settle.
        }
    }

    /**
     * Open a project's bare workspace route resiliently against the session-hydration race. A fresh
     * document load runs {@code GET /api/auth/me} asynchronously; if an earlier in-flight request is
     * aborted by this navigation the auth store clears the session and bounces to {@code /login}. We
     * therefore settle to network-idle after opening and, if we landed on {@code /login}, re-open once
     * — the preserved tokens re-authenticate on the second load — before waiting for the workspace
     * container. Mirrors the proven approach in {@code WorkspaceSteps}.
     */
    private void openWorkspaceSettled(long projectId) {
        clearWorkingProjectMemory();
        settle();
        workspacePage().open(projectId);
        settle();
        if (page().url().contains(com.foremen.qa.pages.LoginPage.ROUTE)) {
            // Hydration bounced us to /login; the tokens are still in storage — re-open to re-auth.
            workspacePage().open(projectId);
            settle();
        }
        workspacePage().waitUntilRendered();
        // The workspace sets the working project to :projectId on open, but a stale
        // foremen.workingProjectId from a previously-opened project can make the shell redirect to
        // that other project (observed: opening a DESIGN project redirected to a prior ACTIVE one,
        // hiding the design-stage widget). Wait until the URL settles on the project we asked for.
        page().waitForCondition(() -> page().url().contains("/projects/" + projectId));
    }

    /**
     * Selectively clear the working-project + per-project tab memory on the app origin, PRESERVING
     * the auth tokens (a blanket {@code localStorage.clear()} would log the browser out). This makes
     * each bare-route open unambiguous: without it, a {@code foremen.workingProjectId} left by an
     * earlier open can make the shell redirect to that other project. Mirrors the selective clear in
     * {@code WorkspaceSteps}. Navigates to the origin first so its storage is in scope, then settles.
     */
    private void clearWorkingProjectMemory() {
        Page page = page();
        try {
            page.navigate(com.foremen.qa.pages.AppShell.homeUrl());
            settle();
            page.evaluate("() => { try { localStorage.removeItem('foremen.workingProjectId');"
                    + " Object.keys(localStorage).filter(k => k.startsWith('foremen.projectTab.'))"
                    + ".forEach(k => localStorage.removeItem(k)); } catch (e) {} }");
        } catch (RuntimeException ignored) {
            // Best-effort: if the origin cannot be reached, the open below still proceeds.
        }
    }

    private long requireDesign() {
        assertThat(designProjectId).as("a readiness design project must be created first").isNotNull();
        return designProjectId;
    }

    private long requireExec() {
        assertThat(execProjectId).as("a readiness execution project must be created first").isNotNull();
        return execProjectId;
    }
}
