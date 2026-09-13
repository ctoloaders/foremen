package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the Work Catalog page ({@code /catalog/works}, Requirement 8.2).
 *
 * <p>Mirrors the real frontend {@code WorkCatalogPage}: a localized {@code <h1>} page title, a
 * {@code Create} button (rendered with the {@code WORK_CATALOG:CREATE} grant — the seeded ADMIN has
 * it) above the shared {@code DataTable}, and a right-side {@code WorkItemFormSheet} overlay for
 * creating a work item. The list itself (rows, columns, search, pagination) is covered by the
 * generic {@link DataTablePage}; this page object adds the route, the create entry point, and the
 * create form.
 *
 * <p>The create form exposes the two FK reference selects the requirement calls for: the work
 * category ({@code #work-item-category}, options from {@code /api/work-categories}) and the unit
 * ({@code #work-item-unit}, options from {@code /api/measurement-units}). Both are the shared
 * {@code AsyncEntitySelect} combobox: a {@code role=combobox} trigger button that opens a popover
 * containing a search {@code input} and {@code role=option} rows. Selecting a category/unit picks the
 * matching option by its (server-resolved, run-created) display name. The name fields are
 * {@code #work-item-nameRU}/{@code #work-item-namePL}; submit is the sheet-footer {@code type=submit}
 * button.
 */
public final class WorkCatalogPage {

    /** Work Catalog route (centralized here per the design). */
    public static final String ROUTE = "/catalog/works";

    private final Page page;

    public WorkCatalogPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the work-catalog route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate directly to {@code /catalog/works} (deep-link). */
    public WorkCatalogPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is on the {@code /catalog/works} route. */
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

    /** Open the create-work-item sheet by clicking the Create button, waiting for the dialog. */
    public WorkCatalogPage openCreate() {
        createButton().first().click();
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        page.locator("#work-item-nameRU").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** The create/edit sheet dialog container (Radix dialog role). */
    private Locator dialog() {
        return page.getByRole(AriaRole.DIALOG);
    }

    /**
     * Pick the work category in the FK combobox by its display name. Opens the
     * {@code #work-item-category} combobox, searches for {@code name}, and clicks the matching
     * {@code role=option} row.
     */
    public WorkCatalogPage selectWorkCategory(String name) {
        pickAsyncEntity("#work-item-category", name);
        return this;
    }

    /**
     * Pick the unit in the FK combobox by its display name. Opens the {@code #work-item-unit}
     * combobox, searches for {@code name}, and clicks the matching {@code role=option} row.
     */
    public WorkCatalogPage selectUnit(String name) {
        pickAsyncEntity("#work-item-unit", name);
        return this;
    }

    /**
     * Open an {@code AsyncEntitySelect} by its trigger id, type {@code name} into its search box, and
     * click the option whose text contains {@code name}. Waits for the popover options to render
     * before clicking so the pick is stable against the debounced server search.
     */
    private void pickAsyncEntity(String triggerId, String name) {
        page.locator(triggerId).click();
        // The open popover renders a single search input; type to narrow the options list.
        Locator search = page.getByRole(AriaRole.TEXTBOX).last();
        search.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        search.fill(name);
        Locator option = page.getByRole(AriaRole.OPTION)
                .filter(new Locator.FilterOptions().setHasText(name));
        option.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        option.first().click();
    }

    /** Fill the Russian and Polish name inputs. */
    public WorkCatalogPage fillNames(String nameRu, String namePl) {
        page.locator("#work-item-nameRU").fill(nameRu);
        page.locator("#work-item-namePL").fill(namePl);
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
