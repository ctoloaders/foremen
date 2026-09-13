package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Page;

/**
 * Route-parameterized page object for the nine flat dictionary admin pages (Requirement 8.1).
 *
 * <p>Every dictionary route renders the same shape: a localized {@code <h1>} page title above the
 * shared {@code DataTable}. Because the pages are structurally identical (only the route and the
 * localized column labels differ), one parameterized page object covers all nine. It is constructed
 * with the dictionary's <em>route</em> (e.g. {@code /measurement-units}) so the same class drives
 * the data-driven {@code dictionaries.feature} Scenario Outline over every route.
 *
 * <p>The nine dictionary routes (from the frontend {@code NAV_CONFIG} "Dictionaries" group and the
 * router) are:
 * <ul>
 *   <li>{@code /measurement-units}</li>
 *   <li>{@code /currencies}</li>
 *   <li>{@code /vat-rates}</li>
 *   <li>{@code /room-types}</li>
 *   <li>{@code /work-categories}</li>
 *   <li>{@code /delivery-categories}</li>
 *   <li>{@code /delivery-statuses}</li>
 *   <li>{@code /material-categories}</li>
 *   <li>{@code /offer-packages}</li>
 * </ul>
 *
 * <p>The list itself (rows, column headers, search, pagination) is covered by the generic
 * {@link DataTablePage}; this page object only adds the per-route navigation and the page heading.
 */
public final class DictionaryPage {

    /** The nine flat dictionary routes, in the order they appear in the Dictionaries nav group. */
    public static final String[] DICTIONARY_ROUTES = {
            "/measurement-units",
            "/currencies",
            "/vat-rates",
            "/room-types",
            "/work-categories",
            "/delivery-categories",
            "/delivery-statuses",
            "/material-categories",
            "/offer-packages",
    };

    private final Page page;
    private final String route;

    public DictionaryPage(Page page, String route) {
        this.page = page;
        this.route = route;
    }

    /** The dictionary route this page object is bound to (e.g. {@code /currencies}). */
    public String route() {
        return route;
    }

    /** Absolute URL of the dictionary route against the configured frontend base URL. */
    public String url() {
        return TestConfig.frontendUrl() + route;
    }

    /** Navigate directly to the dictionary route (deep-link). */
    public DictionaryPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is currently on this dictionary's route. */
    public boolean isOpen() {
        String url = page.url();
        return url.endsWith(route) || url.contains(route + "?") || url.contains(route + "#");
    }

    /** The generic list view for this dictionary (rows, columns, search, pagination). */
    public DataTablePage table() {
        return new DataTablePage(page);
    }
}
