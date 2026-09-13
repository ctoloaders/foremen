package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object for the Users admin page ({@code /users}, Requirement 7.3).
 *
 * <p>Mirrors the real frontend {@code UsersPage}: a localized {@code <h1>} page title, a
 * {@code Create} button (rendered only with the {@code USERS:CREATE} grant — the seeded ADMIN has
 * it) above the shared {@code DataTable}. The list itself (rows, columns, search, pagination) is
 * covered by the generic {@link DataTablePage}; this page object adds only the Users-specific route
 * and the create entry point. Creating/filling/submitting the user form is delegated to
 * {@link UserFormSheet}, which opens as a right-side sheet overlay.
 *
 * <p>The Create button's label is localized ({@code users.actions.create}), so it is located by its
 * leading {@code Plus} icon position — it is the single primary button above the table (the only
 * {@code <button>} carrying the {@code lucide-plus} icon in the page toolbar).
 */
public final class UsersPage {

    /** Users route (centralized here per the design). */
    public static final String ROUTE = "/users";

    private final Page page;

    public UsersPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the users route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate directly to {@code /users} (deep-link). */
    public UsersPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is on the {@code /users} route. */
    public boolean isOpen() {
        return page.url().contains(ROUTE);
    }

    /** The localized page-title heading ({@code users.pageTitle}). */
    public Locator heading() {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setLevel(1));
    }

    /** The generic list view for this page (rows, columns, search, pagination). */
    public DataTablePage table() {
        return new DataTablePage(page);
    }

    /**
     * The Create button above the table. It carries a leading {@code lucide-plus} icon and is the
     * primary create action; located by that icon to stay locale-independent.
     */
    public Locator createButton() {
        return page.locator("button:has(svg.lucide-plus)");
    }

    /**
     * Open the create-user sheet by clicking the Create button, returning a {@link UserFormSheet}
     * bound to the same page once the sheet's dialog has appeared.
     */
    public UserFormSheet openCreate() {
        createButton().first().click();
        UserFormSheet sheet = new UserFormSheet(page);
        sheet.waitUntilOpen();
        return sheet;
    }
}
