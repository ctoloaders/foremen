package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the project workspace shell (FOR-05-01) rendered at
 * {@code /projects/:projectId} and {@code /projects/:projectId/:tab} inside the {@code AppShell}.
 *
 * <p>Mirrors the real frontend {@code ProjectWorkspacePage}: the workspace page container
 * ({@code [data-testid=project-workspace-page]}), the header + mode caption, the readiness widget
 * (design stage only), the "project unavailable" state, and the active tab panel. Tab-strip and
 * design-selector interactions live in the sibling {@link WorkspaceTabs} page object; the always-on
 * working-project surface (sidebar button / mobile chip) lives in {@link WorkingProjectSurface}.
 *
 * <p>The workspace URL is {@code /projects/:projectId} (bare, which the app normalizes with a
 * {@code replace} to {@code /projects/:projectId/:tab}) and {@code /projects/:projectId/:tab} (a
 * deep link to a specific tab). This page object owns those route helpers so scenarios never build
 * URLs by hand. It holds no assertions — steps assert against these intention-revealing queries.
 */
public final class ProjectWorkspacePage {

    private final Page page;

    public ProjectWorkspacePage(Page page) {
        this.page = page;
    }

    // ---- Route helpers ----

    /** Absolute bare workspace URL {@code {frontend}/projects/{projectId}} (no tab). */
    public static String workspaceUrl(long projectId) {
        return TestConfig.frontendUrl() + "/projects/" + projectId;
    }

    /** Absolute deep-link workspace URL {@code {frontend}/projects/{projectId}/{tab}}. */
    public static String workspaceTabUrl(long projectId, String tab) {
        return TestConfig.frontendUrl() + "/projects/" + projectId + "/" + tab;
    }

    // ---- Navigation ----

    /** Navigate to the bare {@code /projects/:projectId} route (the app normalizes to a tab). */
    public ProjectWorkspacePage open(long projectId) {
        page.navigate(workspaceUrl(projectId));
        return this;
    }

    /** Navigate directly to {@code /projects/:projectId/:tab} (a deep link to a specific tab). */
    public ProjectWorkspacePage openTab(long projectId, String tab) {
        page.navigate(workspaceTabUrl(projectId, tab));
        return this;
    }

    // ---- Readiness / structure ----

    /** The workspace page container. */
    public Locator pageContainer() {
        return page.locator("[data-testid=project-workspace-page]");
    }

    /** {@code true} once the workspace page container is visible. */
    public boolean isRendered() {
        return pageContainer().count() > 0 && pageContainer().first().isVisible();
    }

    /** Wait until the workspace page container has rendered. */
    public ProjectWorkspacePage waitUntilRendered() {
        pageContainer().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** The browser's current URL. */
    public String currentUrl() {
        return page.url();
    }

    // ---- Header + mode caption (Requirement 9) ----

    /** The workspace header element. */
    public Locator header() {
        return page.locator("[data-testid=workspace-header]");
    }

    /** The stage mode caption element ({@code workspace.mode.design} / {@code .execution}). */
    public Locator modeCaption() {
        return page.locator("[data-testid=workspace-mode-caption]");
    }

    /** The trimmed text of the mode caption (e.g. RU "Проектирование" / "В работе"). */
    public String modeCaptionText() {
        Locator caption = modeCaption();
        if (caption.count() == 0) {
            return "";
        }
        String text = caption.first().innerText();
        return text == null ? "" : text.trim();
    }

    // ---- Readiness widget (design stage only, Requirement 8) ----

    /** The design-stage readiness widget (present only in the design stage). */
    public Locator readinessWidget() {
        return page.locator("[data-testid=workspace-readiness-widget]");
    }

    /** {@code true} when the readiness widget is rendered and visible. */
    public boolean isReadinessWidgetVisible() {
        return readinessWidget().count() > 0 && readinessWidget().first().isVisible();
    }

    // ---- Project unavailable (Requirement 1.6) ----

    /** The graceful "project unavailable" state ({@code workspace.notFound}). */
    public Locator projectUnavailable() {
        return page.locator("[data-testid=workspace-project-unavailable]");
    }

    /** {@code true} when the "project unavailable" state is rendered and visible. */
    public boolean isProjectUnavailableVisible() {
        return projectUnavailable().count() > 0 && projectUnavailable().first().isVisible();
    }

    // ---- Active panel ----

    /** The active tab panel container. */
    public Locator activePanel() {
        return page.locator("[data-testid=workspace-tab-panel]");
    }

    /** The overview panel (the one real, non-placeholder panel shipped by FOR-05-01). */
    public Locator overviewPanel() {
        return page.locator("[data-testid=workspace-overview-tab]");
    }

    /** The generic placeholder panel; carries {@code data-tab} / {@code data-project-id} attrs. */
    public Locator placeholderPanel() {
        return page.locator("[data-testid=workspace-placeholder-tab]");
    }

    /**
     * The {@code data-tab} attribute of the rendered placeholder panel, identifying which tab's
     * placeholder is active, or {@code null} when the overview (real) panel is shown instead.
     */
    public String activePanelTestId() {
        if (overviewPanel().count() > 0 && overviewPanel().first().isVisible()) {
            return "workspace-overview-tab";
        }
        if (placeholderPanel().count() > 0 && placeholderPanel().first().isVisible()) {
            return "workspace-placeholder-tab";
        }
        return null;
    }

    /** The overview edit affordance ({@code workspace.edit}, RU "Редактировать проект"). */
    public Locator overviewEditAffordance() {
        return page.getByText("Редактировать проект");
    }
}
