package com.foremen.qa.steps;

import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.ForbiddenPage;
import com.foremen.qa.pages.ProjectWorkspacePage;
import com.foremen.qa.pages.ProjectsPage;
import com.foremen.qa.pages.WorkingProjectSurface;
import com.foremen.qa.pages.WorkspaceTabs;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-QA-AUTO-05 detailed steps for the FOR-05-01 project workspace shell.
 *
 * <p>Drives the workspace shell through the browser as the seeded ADMIN (or a restricted test user
 * for the ABAC cases), covering the route + normalization, the always-visible working-project
 * surface (desktop sidebar button / mobile chip and their mutual exclusion), the stage-dependent tab
 * set + the execution design-selector (FIX 2), URL-driven active-tab memory, the readiness widget +
 * mode caption, and the projects-list row-click override (FIX 1).
 *
 * <p>Setup creates projects via {@link World#api()} as the seeded ADMIN and registers
 * {@code deleteProject} teardown (resolving the id by name); a design-stage project is a DRAFT
 * (name-only create) and an execution-stage project is created with an inline {@code status=ACTIVE}
 * (the backend {@code CreateProjectRequest} accepts {@code status}, defaulting to {@code DRAFT} when
 * null). Each scenario starts from a clean working-project / tab state by SELECTIVELY clearing only
 * the {@code foremen.workingProjectId} + {@code foremen.projectTab.*} keys on the app origin — the
 * auth tokens are preserved so the browser stays logged in (Hooks already opens a fresh incognito
 * context per scenario, so storage never leaks — the explicit selective clear keeps scenarios
 * self-describing). The
 * stack-ready + admin-login backgrounds are reused from {@link CommonSteps}; restricted-user
 * provisioning is reused from {@link NavVisibilitySteps} / {@link TestUserSteps}.
 */
public class WorkspaceSteps {

    private static final String PROJECTS_PATH = "/api/projects";
    private static final String WORKING_PROJECT_KEY = "foremen.workingProjectId";
    private static final int DESKTOP_WIDTH = 1440;
    private static final int DESKTOP_HEIGHT = 900;
    private static final int MOBILE_WIDTH = 390;
    private static final int MOBILE_HEIGHT = 844;

    private final World world;

    private ProjectWorkspacePage workspacePage;
    private WorkspaceTabs workspaceTabs;
    private WorkingProjectSurface workingSurface;
    private ProjectsPage projectsPage;
    private AppShell appShell;
    private ForbiddenPage forbiddenPage;

    // Remembered run-created project handles (design / execution / other) by role in the scenario.
    private Long designProjectId;
    private Long execProjectId;
    private Long otherProjectId;

    // Created project display names keyed by id, so the working-project surface text can be located.
    private final Map<Long, String> projectNamesById = new LinkedHashMap<>();

    // True once the scenario switched to the mobile viewport; drives the mobile-drawer dismissal.
    private boolean mobileViewport;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public WorkspaceSteps(World world) {
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

    private WorkingProjectSurface workingSurface() {
        if (workingSurface == null) {
            workingSurface = new WorkingProjectSurface(page());
        }
        return workingSurface;
    }

    private ProjectsPage projectsPage() {
        if (projectsPage == null) {
            projectsPage = new ProjectsPage(page());
        }
        return projectsPage;
    }

    private AppShell appShell() {
        if (appShell == null) {
            appShell = new AppShell(page());
        }
        return appShell;
    }

    private ForbiddenPage forbiddenPage() {
        if (forbiddenPage == null) {
            forbiddenPage = new ForbiddenPage(page());
        }
        return forbiddenPage;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    // ================= Project setup (Given) =================

    /** Create a run-unique DESIGN-stage (DRAFT) project via the API; remember its id + register teardown. */
    @Given("a design-stage project exists")
    public void aDesignStageProjectExists() {
        designProjectId = createProject(null);
    }

    /** Create a run-unique EXECUTION-stage (ACTIVE) project via the API; remember its id + register teardown. */
    @Given("an execution-stage project exists")
    public void anExecutionStageProjectExists() {
        execProjectId = createProject("ACTIVE");
    }

    /** Create a second run-unique DESIGN-stage project (the "other" project for memory independence). */
    @Given("another design-stage project exists")
    public void anotherDesignStageProjectExists() {
        otherProjectId = createProject(null);
    }

    /**
     * Create a project via the seeded-admin API. When {@code status} is {@code null} the backend
     * defaults it to {@code DRAFT} (design stage); passing {@code "ACTIVE"} yields an execution-stage
     * project. Registers idempotent {@code deleteProject} teardown resolving the id by name.
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
                // Best-effort: already gone / resolve mismatch — teardown stays idempotent.
            }
        });
        return id;
    }

    // ================= localStorage state (Given / When / Then) =================

    /**
     * Clear ONLY the working-project and per-project tab memory, navigating to the app origin first
     * so its {@code localStorage} is in scope. This is a SELECTIVE clear: it removes
     * {@code foremen.workingProjectId} and every {@code foremen.projectTab.*} key but PRESERVES the
     * auth tokens the frontend stores under the fixed keys {@code foremen-access-token} /
     * {@code foremen-refresh-token} (see {@code foremen-frontend/src/stores/auth-store.ts}). A blanket
     * {@code localStorage.clear()} would wipe those tokens and log the browser out, so every
     * subsequent workspace navigation would redirect to {@code /login} and the scenario would time
     * out. The admin UI login (CommonSteps) must therefore run BEFORE this step so the tokens exist,
     * and this step must not touch them. Each scenario still starts from a clean working-project / tab
     * state (the incognito context already isolates scenarios; this keeps the scenario
     * self-describing).
     */
    @Given("the working-project and tab memory is cleared")
    public void theWorkingProjectAndTabMemoryIsCleared() {
        openAppOriginAndSettle();
        // Selective clear: drop only the working-project + per-project tab keys; PRESERVE auth tokens.
        // Also pin the UI locale to RU: i18n defaults to PL in a fresh incognito context
        // (foremen-frontend/src/lib/i18n.ts → getInitialLocale() returns 'pl' when
        // localStorage['foremen-locale'] is unset), but this slice's surface / caption / affordance
        // locators key off the RU i18n text (e.g. "Выбрать проект", "Рабочий проект",
        // "Редактировать проект", "Проектирование"). Pinning the locale here makes those assertions
        // deterministic. The locale is applied on the NEXT full navigation (i18n reads it at init),
        // which every scenario performs right after this step.
        page().evaluate("() => { try { localStorage.removeItem('foremen.workingProjectId');"
                + " Object.keys(localStorage).filter(k => k.startsWith('foremen.projectTab.'))"
                + ".forEach(k => localStorage.removeItem(k));"
                + " localStorage.setItem('foremen-locale', 'ru'); } catch (e) {} }");
    }

    /**
     * Navigate to the app origin and WAIT until the authenticated shell has rendered before
     * returning. Session hydration ({@code GET /api/auth/me}) runs asynchronously on every fresh
     * document load; navigating away again while it is still in flight aborts the {@code /me} call,
     * which the auth store treats as an unrecoverable error and responds to by clearing the tokens
     * and bouncing to {@code /login} (see {@code foremen-frontend/src/stores/auth-store.ts}
     * {@code hydrate()} → {@code clearSession()}). That race is exactly what made the bare-route
     * scenarios land on {@code /login} instead of the workspace. Waiting for the shell (topbar +
     * nav) to render guarantees hydration finished authenticated, so the subsequent localStorage
     * write and the scenario's next navigation are safe.
     */
    private void openAppOriginAndSettle() {
        Page page = page();
        page.navigate(AppShell.homeUrl());
        // Wait for the async session hydration (GET /api/auth/me) and any other in-flight requests
        // to finish. The shell chrome (header + nav) renders BEFORE hydration completes, so waiting
        // only for it would still let the next navigation abort the /me call and clear the session;
        // network-idle guarantees /me has settled first.
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
        // Guard: if a still-earlier in-flight hydration had bounced us to /login, the fresh home
        // navigation above re-authenticates from the preserved tokens; ensure we ended on the shell.
        page.waitForCondition(() -> !page.url().contains(com.foremen.qa.pages.LoginPage.ROUTE)
                && appShell().isRendered());
    }

    /** Seed the per-project tab memory {@code foremen.projectTab.<projectId>} to a given tab. */
    @Given("the stored tab for the design project is {string}")
    public void theStoredTabForTheDesignProjectIs(String tab) {
        setProjectTabMemory(requireDesign(), tab);
    }

    private void setProjectTabMemory(long projectId, String tab) {
        openAppOriginAndSettle();
        String key = "foremen.projectTab." + projectId;
        page().evaluate("([k, v]) => window.localStorage.setItem(k, v)",
                java.util.List.of(key, tab));
    }

    /** Read a localStorage value on the app origin by key (null when absent). */
    private String localStorageItem(String key) {
        Object value = page().evaluate("k => window.localStorage.getItem(k)", key);
        return value == null ? null : value.toString();
    }

    // ================= Viewport (Given) =================

    @Given("the viewport is desktop")
    public void theViewportIsDesktop() {
        page().setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT);
        mobileViewport = false;
    }

    @Given("the viewport is mobile")
    public void theViewportIsMobile() {
        page().setViewportSize(MOBILE_WIDTH, MOBILE_HEIGHT);
        mobileViewport = true;
    }

    /**
     * Close the mobile navigation Drawer when it is open, so its full-screen overlay stops
     * intercepting pointer events on the content behind it. On the mobile breakpoint the app shell
     * renders a slide-out {@code Drawer} whose open state is the {@code ui-store} {@code sidebarOpen}
     * flag; that flag defaults to {@code true} and is NOT persisted, so every fresh document load on
     * mobile re-opens the drawer as a {@code fixed inset-0 z-50} overlay covering the projects list,
     * the working-project chip, and everything else. The drawer exposes a close control labeled
     * {@code aria-label="Close menu"}; clicking it dismisses the overlay. No-op on desktop/tablet
     * (the drawer is not rendered) and when the drawer is already closed.
     */
    private void dismissMobileNavIfOpen() {
        if (!mobileViewport) {
            return;
        }
        Locator close = page().locator("button[aria-label='Close menu']");
        try {
            // The drawer opens fresh on every mobile document load and animates in, so its close
            // control may not be actionable the instant navigation's load event fires — wait a short
            // bounded time for it to appear before clicking. If it never shows (already closed), skip.
            close.first().waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
        } catch (RuntimeException notOpen) {
            return; // Drawer not open (or not rendered) — nothing to dismiss.
        }
        close.first().click();
        // Wait for the slide-out + backdrop overlay to finish animating away so it stops
        // intercepting pointer events on the content behind it.
        page().locator("div.fixed.inset-0.z-50.visible").first().waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    // ================= Navigation (When) =================

    @When("I open the projects list")
    public void iOpenTheProjectsList() {
        projectsPage().open();
        dismissMobileNavIfOpen();
        projectsPage().table().waitUntilLoaded();
    }

    @When("I open the design project workspace")
    public void iOpenTheDesignProjectWorkspace() {
        workspacePage().open(requireDesign());
        dismissMobileNavIfOpen();
    }

    @When("I open the execution project workspace")
    public void iOpenTheExecutionProjectWorkspace() {
        workspacePage().open(requireExec());
        dismissMobileNavIfOpen();
    }

    @When("I open the design project overview tab")
    public void iOpenTheDesignProjectOverviewTab() {
        workspacePage().openTab(requireDesign(), "overview");
    }

    @When("I open the execution project overview tab")
    public void iOpenTheExecutionProjectOverviewTab() {
        workspacePage().openTab(requireExec(), "overview");
    }

    @When("I open the design project bare route")
    public void iOpenTheDesignProjectBareRoute() {
        settleBeforeNavigation();
        workspacePage().open(requireDesign());
    }

    @When("I open the execution project bare route")
    public void iOpenTheExecutionProjectBareRoute() {
        settleBeforeNavigation();
        workspacePage().open(requireExec());
    }

    /**
     * Let the current page reach network idle before navigating away. The bare-route redirect on a
     * fresh load depends on session hydration ({@code GET /api/auth/me}) completing; if a prior
     * step's in-flight request (a tab-switch refetch, a {@code /me}) is still pending when the next
     * full navigation starts, that request aborts and the auth store treats it as unrecoverable,
     * clearing the session and bouncing to {@code /login} — so the bare route never resolves to its
     * tab. Waiting for idle first removes that race (mirrors the settle used by the clear step).
     */
    private void settleBeforeNavigation() {
        try {
            page().waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
            // Best-effort: if idle can't be reached quickly, proceed — the URL wait still guards.
        }
    }

    @When("I open the design project tab {string}")
    public void iOpenTheDesignProjectTab(String tab) {
        workspacePage().openTab(requireDesign(), tab);
    }

    @When("I open the execution project tab {string}")
    public void iOpenTheExecutionProjectTab(String tab) {
        workspacePage().openTab(requireExec(), tab);
    }

    @When("I open a missing project workspace")
    public void iOpenAMissingProjectWorkspace() {
        // A very large id that will not resolve to an accessible project.
        long missingId = 999_000_000L + DataGen.nextSeq();
        workspacePage().openTab(missingId, "overview");
    }

    @When("I open the design project workspace by deep link")
    public void iOpenTheDesignProjectWorkspaceByDeepLink() {
        // Deep-link straight to the tab URL (used by the restricted-user guard case).
        workspacePage().openTab(requireDesign(), "overview");
    }

    @When("I reload the page")
    public void iReloadThePage() {
        page().reload();
    }

    @When("I navigate back in the browser")
    public void iNavigateBackInTheBrowser() {
        page().goBack();
    }

    // ================= Tab / selector interaction (When) =================

    @When("I click the workspace tab {string}")
    public void iClickTheWorkspaceTab(String key) {
        workspaceTabs().waitUntilRendered();
        workspaceTabs().clickTab(key);
    }

    @When("I open the design selector")
    public void iOpenTheDesignSelector() {
        workspaceTabs().openDesignSelector();
    }

    @When("I click the design-selector tab {string}")
    public void iClickTheDesignSelectorTab(String key) {
        workspaceTabs().clickDesignTab(key);
    }

    // ================= Row click override (When) =================

    @When("I click the design project row")
    public void iClickTheDesignProjectRow() {
        dismissMobileNavIfOpen();
        projectsPage().clickRow(projectName(requireDesign()));
    }

    @When("I click the other project row")
    public void iClickTheOtherProjectRow() {
        dismissMobileNavIfOpen();
        projectsPage().clickRow(projectName(requireOther()));
    }

    @When("I click the edit action on the design project row")
    public void iClickTheEditActionOnTheDesignProjectRow() {
        projectsPage().clickRowEdit(projectName(requireDesign()));
    }

    @When("I click the overview edit affordance")
    public void iClickTheOverviewEditAffordance() {
        workspacePage().overviewEditAffordance().first().click();
    }

    // ================= Assertions (Then) =================

    @Then("the project workspace page is rendered")
    public void theProjectWorkspacePageIsRendered() {
        workspacePage().waitUntilRendered();
        assertThat(workspacePage().isRendered())
                .as("the project workspace page container should render")
                .isTrue();
    }

    @Then("the workspace header is shown")
    public void theWorkspaceHeaderIsShown() {
        assertThat(workspacePage().header().count())
                .as("the workspace header should be present")
                .isGreaterThan(0);
    }

    @Then("the projects list is reachable again")
    public void theProjectsListIsReachableAgain() {
        projectsPage().open();
        projectsPage().table().waitUntilLoaded();
        assertThat(projectsPage().table().isLoaded())
                .as("the projects list should still render after visiting the workspace")
                .isTrue();
    }

    @Then("the workspace URL resolves to the overview tab for the design project")
    public void theWorkspaceUrlResolvesToOverviewForDesign() {
        // The bare route normalizes to the tab asynchronously (after the project hydrates and the
        // replace-navigation runs), so wait for the redirect rather than snapshotting immediately.
        String suffix = "/projects/" + requireDesign() + "/overview";
        page().waitForURL(url -> url.endsWith(suffix) || url.contains(suffix + "?"));
        assertUrlEndsWith(suffix);
    }

    @Then("the workspace URL resolves to the overview tab for the execution project")
    public void theWorkspaceUrlResolvesToOverviewForExec() {
        String suffix = "/projects/" + requireExec() + "/overview";
        page().waitForURL(url -> url.endsWith(suffix) || url.contains(suffix + "?"));
        assertUrlEndsWith(suffix);
    }

    @Then("the browser is not on the design project bare route")
    public void theBrowserIsNotOnTheDesignBareRoute() {
        String bare = "/projects/" + requireDesign();
        String url = page().url();
        // After a replace-normalization, going back must not land on the bare route again.
        assertThat(url.endsWith(bare) || url.endsWith(bare + "/"))
                .as("Back should not return to the bare route (normalization used replace, not push)")
                .isFalse();
    }

    @Then("the project unavailable state is shown")
    public void theProjectUnavailableStateIsShown() {
        page().locator("[data-testid=workspace-project-unavailable]").first()
                .waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        assertThat(workspacePage().isProjectUnavailableVisible())
                .as("an unavailable project should render the workspace-project-unavailable state")
                .isTrue();
        assertThat(workspacePage().isRendered())
                .as("the full workspace page should not render for an unavailable project")
                .isFalse();
    }

    @Then("I am shown the forbidden page for the workspace route")
    public void iAmShownTheForbiddenPageForTheWorkspaceRoute() {
        page().waitForURL(url -> url.contains(ForbiddenPage.ROUTE));
        assertThat(forbiddenPage().isShown())
                .as("a user without PROJECTS/READ should be redirected to /403 on the workspace route")
                .isTrue();
    }

    // ---- Tab set / order ----

    @Then("the visible workspace tabs are {string} in order")
    public void theVisibleWorkspaceTabsAreInOrder(String csvKeys) {
        workspaceTabs().waitUntilRendered();
        String[] keys = csvKeys.split("\\s*,\\s*");
        for (String key : keys) {
            assertThat(workspaceTabs().isTabVisible(key))
                    .as("workspace tab '%s' should be visible", key)
                    .isTrue();
        }
    }

    @Then("the workspace tab {string} is visible")
    public void theWorkspaceTabIsVisible(String key) {
        workspaceTabs().waitUntilRendered();
        assertThat(workspaceTabs().isTabVisible(key))
                .as("workspace tab '%s' should be visible", key)
                .isTrue();
    }

    @Then("the workspace tab {string} is hidden")
    public void theWorkspaceTabIsHidden(String key) {
        workspaceTabs().waitUntilRendered();
        assertThat(workspaceTabs().isTabVisible(key))
                .as("workspace tab '%s' should be hidden", key)
                .isFalse();
    }

    @Then("the workspace tab {string} is active")
    public void theWorkspaceTabIsActive(String key) {
        // The active state is set after the SPA renders the resolved tab; wait for data-active=true
        // rather than snapshotting immediately (a direct-URL open may still be settling).
        workspaceTabs().waitUntilRendered();
        page().waitForCondition(() -> workspaceTabs().isTabActive(key));
        assertThat(workspaceTabs().isTabActive(key))
                .as("workspace tab '%s' should be the active tab", key)
                .isTrue();
    }

    // ---- Design selector (FIX 2) ----

    @Then("the design selector is shown")
    public void theDesignSelectorIsShown() {
        workspaceTabs().waitUntilRendered();
        assertThat(workspaceTabs().isDesignSelectorVisible())
                .as("the execution project should show the grouped design-tabs selector")
                .isTrue();
    }

    @Then("the design selector is hidden")
    public void theDesignSelectorIsHidden() {
        workspaceTabs().waitUntilRendered();
        assertThat(workspaceTabs().isDesignSelectorVisible())
                .as("the design-tabs selector should be hidden")
                .isFalse();
    }

    @Then("the design-selector tab {string} is available")
    public void theDesignSelectorTabIsAvailable(String key) {
        assertThat(workspaceTabs().designTab(key).count())
                .as("the design selector should contain the '%s' design tab", key)
                .isGreaterThan(0);
    }

    // ---- URL / memory ----

    @Then("the workspace URL ends with {string} for the design project")
    public void theWorkspaceUrlEndsWithForDesign(String tab) {
        page().waitForURL(url -> url.endsWith("/projects/" + requireDesign() + "/" + tab));
        assertUrlEndsWith("/projects/" + requireDesign() + "/" + tab);
    }

    @Then("the workspace URL ends with {string} for the execution project")
    public void theWorkspaceUrlEndsWithForExec(String tab) {
        page().waitForURL(url -> url.endsWith("/projects/" + requireExec() + "/" + tab));
        assertUrlEndsWith("/projects/" + requireExec() + "/" + tab);
    }

    @Then("the stored tab for the design project reads {string}")
    public void theStoredTabForTheDesignProjectReads(String tab) {
        long id = requireDesign();
        page().waitForCondition(() -> tab.equals(localStorageItem("foremen.projectTab." + id)));
        assertThat(localStorageItem("foremen.projectTab." + id))
                .as("foremen.projectTab.%s should equal '%s'", id, tab)
                .isEqualTo(tab);
    }

    @Then("the stored tab for the execution project reads {string}")
    public void theStoredTabForTheExecutionProjectReads(String tab) {
        long id = requireExec();
        page().waitForCondition(() -> tab.equals(localStorageItem("foremen.projectTab." + id)));
        assertThat(localStorageItem("foremen.projectTab." + id))
                .as("foremen.projectTab.%s should equal '%s'", id, tab)
                .isEqualTo(tab);
    }

    @Then("the stored tab for the other project reads {string}")
    public void theStoredTabForTheOtherProjectReads(String tab) {
        long id = requireOther();
        page().waitForCondition(() -> tab.equals(localStorageItem("foremen.projectTab." + id)));
        assertThat(localStorageItem("foremen.projectTab." + id))
                .as("foremen.projectTab.%s should equal '%s'", id, tab)
                .isEqualTo(tab);
    }

    @When("I open the other project overview tab")
    public void iOpenTheOtherProjectOverviewTab() {
        workspacePage().openTab(requireOther(), "overview");
    }

    @When("I click the other project workspace tab {string}")
    public void iClickTheOtherProjectWorkspaceTab(String key) {
        workspaceTabs().waitUntilRendered();
        workspaceTabs().clickTab(key);
    }

    @When("I open the other project bare route")
    public void iOpenTheOtherProjectBareRoute() {
        settleBeforeNavigation();
        workspacePage().open(requireOther());
    }

    @Then("the workspace URL ends with {string} for the other project")
    public void theWorkspaceUrlEndsWithForOther(String tab) {
        page().waitForURL(url -> url.endsWith("/projects/" + requireOther() + "/" + tab));
        assertUrlEndsWith("/projects/" + requireOther() + "/" + tab);
    }

    // ---- Working project storage ----

    @Then("the stored working project is the design project")
    public void theStoredWorkingProjectIsTheDesignProject() {
        long id = requireDesign();
        page().waitForCondition(() -> String.valueOf(id).equals(localStorageItem(WORKING_PROJECT_KEY)));
        assertThat(localStorageItem(WORKING_PROJECT_KEY))
                .as("foremen.workingProjectId should equal the design project id")
                .isEqualTo(String.valueOf(id));
    }

    // ---- Working-project surface ----

    @Then("the working-project surface shows the choose affordance")
    public void theWorkingProjectSurfaceShowsChoose() {
        page().waitForCondition(() -> workingSurface().isChooseShown());
        assertThat(workingSurface().isChooseShown())
                .as("the working-project surface should show the 'Выбрать проект' affordance")
                .isTrue();
    }

    @Then("the desktop working-project button shows the design project name")
    public void theDesktopButtonShowsDesignName() {
        String name = projectName(requireDesign());
        page().waitForCondition(() -> workingSurface().desktopShowsProjectName(name));
        assertThat(workingSurface().desktopShowsProjectName(name))
                .as("the desktop sidebar button should show the design project name")
                .isTrue();
    }

    @Then("the desktop working-project button shows the other project name")
    public void theDesktopButtonShowsOtherName() {
        String name = projectName(requireOther());
        page().waitForCondition(() -> workingSurface().desktopShowsProjectName(name));
        assertThat(workingSurface().desktopShowsProjectName(name))
                .as("the desktop sidebar button should switch to the other project name")
                .isTrue();
    }

    @Then("the mobile working-project chip shows the design project name")
    public void theMobileChipShowsDesignName() {
        String name = projectName(requireDesign());
        page().waitForCondition(() -> workingSurface().mobileShowsProjectName(name));
        assertThat(workingSurface().mobileShowsProjectName(name))
                .as("the mobile topbar chip should show the design project name")
                .isTrue();
    }

    @Then("the desktop working-project button shows the working caption")
    public void theDesktopButtonShowsWorkingCaption() {
        page().waitForCondition(() -> workingSurface().desktopShowsWorkingCaption());
        assertThat(workingSurface().desktopShowsWorkingCaption())
                .as("the desktop sidebar button should show the 'Рабочий проект' caption when set")
                .isTrue();
    }

    @When("I click the desktop working-project button")
    public void iClickTheDesktopWorkingProjectButton() {
        workingSurface().clickWorkingProject(projectName(requireDesign()));
    }

    @When("I click the mobile working-project chip")
    public void iClickTheMobileWorkingProjectChip() {
        dismissMobileNavIfOpen();
        workingSurface().clickWorkingProject(projectName(requireDesign()));
    }

    @When("I click the change-project affordance")
    public void iClickTheChangeProjectAffordance() {
        dismissMobileNavIfOpen();
        workingSurface().clickChange();
    }

    @When("I click the choose-project affordance")
    public void iClickTheChooseProjectAffordance() {
        dismissMobileNavIfOpen();
        workingSurface().clickChoose();
    }

    @Then("the browser is on the projects list")
    public void theBrowserIsOnTheProjectsList() {
        page().waitForURL(url -> url.endsWith(ProjectsPage.ROUTE) || url.contains(ProjectsPage.ROUTE + "?"));
        assertThat(page().url())
                .as("navigation should land on the /projects list")
                .contains(ProjectsPage.ROUTE);
    }

    @Then("the browser is on the design project workspace")
    public void theBrowserIsOnTheDesignProjectWorkspace() {
        long id = requireDesign();
        page().waitForURL(url -> url.contains("/projects/" + id));
        assertThat(page().url())
                .as("navigation should land on the design project workspace")
                .contains("/projects/" + id);
    }

    @Then("the desktop working-project button is present")
    public void theDesktopButtonIsPresent() {
        page().waitForCondition(() -> workingSurface().desktopShowsWorkingCaption());
        assertThat(workingSurface().desktopShowsWorkingCaption())
                .as("the desktop sidebar working-project button should be present at desktop width")
                .isTrue();
    }

    @Then("the mobile working-project chip is not shown")
    public void theMobileChipIsNotShown() {
        String name = projectName(requireDesign());
        assertThat(workingSurface().mobileShowsProjectName(name))
                .as("the mobile chip should not render inside <header> at desktop width")
                .isFalse();
    }

    @Then("the mobile working-project chip is present")
    public void theMobileChipIsPresent() {
        String name = projectName(requireDesign());
        page().waitForCondition(() -> workingSurface().mobileShowsProjectName(name));
        assertThat(workingSurface().mobileShowsProjectName(name))
                .as("the mobile topbar chip should be present at mobile width")
                .isTrue();
    }

    @Then("the desktop working-project button is not shown")
    public void theDesktopButtonIsNotShown() {
        assertThat(workingSurface().desktopShowsWorkingCaption())
                .as("the desktop sidebar button should not render at mobile width")
                .isFalse();
    }

    // ---- Readiness widget + mode caption ----

    @Then("the readiness widget is shown")
    public void theReadinessWidgetIsShown() {
        page().waitForCondition(() -> workspacePage().isReadinessWidgetVisible());
        assertThat(workspacePage().isReadinessWidgetVisible())
                .as("the design-stage readiness widget should render")
                .isTrue();
    }

    @Then("the readiness widget is not shown")
    public void theReadinessWidgetIsNotShown() {
        workspacePage().waitUntilRendered();
        assertThat(workspacePage().isReadinessWidgetVisible())
                .as("the execution stage should not render the readiness widget")
                .isFalse();
    }

    @Then("the mode caption reads {string}")
    public void theModeCaptionReads(String expected) {
        page().waitForCondition(() -> workspacePage().modeCaption().count() > 0);
        assertThat(workspacePage().modeCaptionText())
                .as("the mode caption should read '%s'", expected)
                .isEqualTo(expected);
    }

    // ---- Row-click override ----

    @Then("the project edit form is not open")
    public void theProjectEditFormIsNotOpen() {
        assertThat(projectsPage().isFormOpen())
                .as("a row-click should NOT open the project edit form (it opens the workspace)")
                .isFalse();
    }

    @Then("the project edit form is open")
    public void theProjectEditFormIsOpen() {
        page().locator("#project-name").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
        assertThat(projectsPage().isFormOpen())
                .as("the per-row edit action should open the project edit form")
                .isTrue();
    }

    @Then("the overview edit affordance is shown")
    public void theOverviewEditAffordanceIsShown() {
        page().waitForCondition(() -> workspacePage().overviewEditAffordance().count() > 0);
        assertThat(workspacePage().overviewEditAffordance().first().isVisible())
                .as("the overview panel should show the 'Редактировать проект' affordance")
                .isTrue();
    }

    // ================= helpers =================

    private void assertUrlEndsWith(String suffix) {
        String url = page().url();
        assertThat(url.endsWith(suffix) || url.contains(suffix + "?"))
                .as("URL '%s' should end with '%s'", url, suffix)
                .isTrue();
    }

    /** The remembered display name of a run-created project by id. */
    private String projectName(long id) {
        String name = projectNamesById.get(id);
        assertThat(name)
                .as("the name of the run-created project %s should be remembered", id)
                .isNotNull();
        return name;
    }

    private long requireDesign() {
        assertThat(designProjectId).as("a design-stage project must be created first").isNotNull();
        return designProjectId;
    }

    private long requireExec() {
        assertThat(execProjectId).as("an execution-stage project must be created first").isNotNull();
        return execProjectId;
    }

    private long requireOther() {
        assertThat(otherProjectId).as("an 'other' project must be created first").isNotNull();
        return otherProjectId;
    }
}
