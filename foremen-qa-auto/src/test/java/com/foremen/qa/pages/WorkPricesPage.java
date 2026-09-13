package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Page object for the Work Prices page ({@code /catalog/prices}, Requirement 8.3).
 *
 * <p>Mirrors the real frontend {@code WorkPricesPage}: a localized {@code <h1>} page title above the
 * shared {@code DataTable}. The list columns include {@code validTo} (rendered as an em-dash
 * {@code —} when {@code null}) and a {@code current} column rendering a localized badge that is
 * "current" exactly when the price is the active one ({@code validTo == null}, derived server-side).
 *
 * <p>The list itself (rows, columns, search, pagination) is covered by the generic
 * {@link DataTablePage}; this page object adds the route and two current-price affordances:
 * <ul>
 *   <li>{@link #currentBadge()} — the green "current" badge span the {@code CurrentBadge} component
 *       renders for an active price (located by its distinctive colour class, which is
 *       locale-independent);</li>
 *   <li>{@link #rowValidToIsOpen(String)} — whether the row matching a work-item name shows an
 *       open-ended {@code validTo} (the {@code —} placeholder), the visible signal of a current
 *       price.</li>
 * </ul>
 */
public final class WorkPricesPage {

    /** Work Prices route (centralized here per the design). */
    public static final String ROUTE = "/catalog/prices";

    /** The em-dash the frontend renders for a {@code null} {@code validTo} (an open-ended price). */
    public static final String OPEN_VALID_TO = "\u2014";

    private final Page page;

    public WorkPricesPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the work-prices route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate directly to {@code /catalog/prices} (deep-link). */
    public WorkPricesPage open() {
        page.navigate(url());
        return this;
    }

    /** {@code true} when the browser is on the {@code /catalog/prices} route. */
    public boolean isOpen() {
        return page.url().contains(ROUTE);
    }

    /** The generic list view for this page (rows, columns, search, pagination). */
    public DataTablePage table() {
        return new DataTablePage(page);
    }

    /**
     * The "current" badge the {@code CurrentBadge} component renders for an active price. It carries
     * the distinctive green colour class {@code bg-[#22c55e]/15}, which is locale-independent (the
     * badge text itself is localized). {@code count() > 0} means at least one current price is shown.
     */
    public Locator currentBadge() {
        return page.locator("[class*='bg-[#22c55e]']");
    }

    /**
     * {@code true} when the list row matching {@code workItemName} shows an open-ended
     * {@code validTo} (the {@code —} placeholder) — the visible signal of a current price. Uses the
     * generic table's row lookup by text.
     */
    public boolean rowValidToIsOpen(String workItemName) {
        Locator row = table().rowByText(workItemName).first();
        if (row.count() == 0) {
            return false;
        }
        String rowText = row.innerText();
        return rowText != null && rowText.contains(OPEN_VALID_TO);
    }
}
