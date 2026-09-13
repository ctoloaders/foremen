package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object for the Roles admin page ({@code /roles}, Requirement 7.2).
 *
 * <p>The real frontend {@code RolesPage} is a two-tab view (a Radix {@code Tabs}, so tab triggers
 * carry {@code role="tab"} and the active panel {@code role="tabpanel"}):
 * <ul>
 *   <li><b>List</b> (first tab) — the shared {@code DataTable} of roles (code, name, description,
 *       system), covered by {@link DataTablePage},</li>
 *   <li><b>Matrix</b> (second tab) — the access/permission matrix ({@link RoleMatrix}): a grid of
 *       role rows × resource columns with toggleable C/R/U/D operation controls.</li>
 * </ul>
 * The tab labels are localized ({@code roles.tabs.list} / {@code roles.tabs.matrix}), so the tabs are
 * located by their order (list first, matrix second) rather than by text.
 */
public final class RolesPage {

    /** Roles route (centralized here per the design). */
    public static final String ROUTE = "/roles";

    private final Page page;

    public RolesPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the roles route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate directly to {@code /roles} (deep-link). */
    public RolesPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is on the {@code /roles} route. */
    public boolean isOpen() {
        return page.url().contains(ROUTE);
    }

    /** The localized page-title heading ({@code roles.pageTitle}). */
    public Locator heading() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1));
    }

    /** The generic roles list view (rows, columns, search, pagination) — the List tab's content. */
    public DataTablePage table() {
        return new DataTablePage(page);
    }

    /** All tab triggers on the page ({@code role=tab}); list is first, matrix is second. */
    private Locator tabs() {
        return page.getByRole(AriaRole.TAB);
    }

    /** Click the List tab (the first tab). */
    public RolesPage openListTab() {
        tabs().nth(0).click();
        return this;
    }

    /**
     * Click the Matrix tab (the second tab) and return a {@link RoleMatrix} bound to the same page.
     * The access matrix renders the resource × operation controls.
     */
    public RoleMatrix openMatrixTab() {
        tabs().nth(1).click();
        RoleMatrix matrix = new RoleMatrix(page);
        matrix.waitUntilVisible();
        return matrix;
    }
}
