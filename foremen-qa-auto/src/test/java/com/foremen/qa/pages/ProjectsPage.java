package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the Projects page ({@code /projects}, Requirement 8.4).
 *
 * <p>Mirrors the real frontend {@code ProjectsPage}: a localized {@code <h1>} page title, a
 * {@code Create} button (rendered with the {@code PROJECTS:CREATE} grant — the seeded ADMIN has it)
 * above the shared {@code DataTable}, and a right-side {@code ProjectFormSheet} overlay for creating
 * a project. The list itself (rows, columns, search, pagination) is covered by the generic
 * {@link DataTablePage}; this page object adds the route, the create entry point, and the create
 * form's happy path.
 *
 * <p>The create form's only required field is the project name ({@code #project-name}); area, dates,
 * status, address, team, and client are optional and left at their defaults for the smoke happy
 * path. Submit is the sheet-footer {@code type=submit} button. On success the sheet closes and a
 * success toast is shown; the created project appears in the list.
 */
public final class ProjectsPage {

    /** Projects route (centralized here per the design). */
    public static final String ROUTE = "/projects";

    private final Page page;

    public ProjectsPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the projects route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate directly to {@code /projects} (deep-link). */
    public ProjectsPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is on the {@code /projects} route. */
    public boolean isOpen() {
        return page.url().contains(ROUTE);
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

    /** Open the create-project sheet by clicking the Create button, waiting for the dialog. */
    public ProjectsPage openCreate() {
        createButton().first().click();
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        page.locator("#project-name").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** The create/edit sheet dialog container (Radix dialog role). */
    private Locator dialog() {
        return page.getByRole(AriaRole.DIALOG);
    }

    /** Fill the required project name. */
    public ProjectsPage fillName(String name) {
        page.locator("#project-name").fill(name);
        return this;
    }

    /** The footer submit button ({@code type=submit}) inside the create sheet. */
    public Locator submitButton() {
        return dialog().locator("button[type=submit]");
    }

    /** Submit the create form. Does not wait for the toast; the step asserts the result. */
    public void submit() {
        submitButton().first().click();
    }

    /** Wait until the sheet dialog has closed (after a successful create). */
    public void waitUntilFormClosed() {
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }
}
