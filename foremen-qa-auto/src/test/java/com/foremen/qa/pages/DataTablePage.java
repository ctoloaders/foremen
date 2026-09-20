package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic page object for the shared DataTable list component (Requirements 8.1, 7.1).
 *
 * <p>Every list/dictionary/admin page in the frontend renders the same {@code DataTable}, so this
 * one page object covers rows, column headers, pagination, and search across all of them. It maps
 * to the real component structure:
 * <ul>
 *   <li><b>search</b> — a debounced text input in the toolbar rendered with the {@code pl-9} class
 *       (icon padding). Located as {@code input.pl-9}.</li>
 *   <li><b>table</b> — a {@code <table>} with {@code <thead><tr><th>…</th></tr></thead>} and a
 *       {@code <tbody>} of {@code <tr>} rows.</li>
 *   <li><b>column headers</b> — each {@code <th>} holds a {@code <span>} with the localized column
 *       label (inside a sort button when sortable).</li>
 *   <li><b>pagination</b> — previous/next {@code <button>}s (located by their leading/trailing
 *       position), a {@code "page / totalPages"} indicator, and a page-size select.</li>
 * </ul>
 * On the mobile breakpoint the table is replaced by cards; the smoke suite runs the default desktop
 * viewport, so this page object targets the table representation.
 */
public final class DataTablePage {

    private final Page page;

    public DataTablePage(Page page) {
        this.page = page;
    }

    // ---- Readiness ----

    /**
     * {@code true} once the list has loaded to a stable state: either the table (with a header row)
     * is present, or the empty-state has rendered. Waits are handled by Playwright auto-waiting in
     * the more specific waits below; this is a non-blocking predicate.
     */
    public boolean isLoaded() {
        return table().count() > 0 || mobileCards().count() > 0;
    }

    /**
     * The mobile card list rendered by {@code DataTableCards} in place of the table on the mobile
     * breakpoint. Each row is a {@code div} with the exact class combo {@code rounded-lg border
     * bg-card p-4} — matching all four keeps this from colliding with other {@code bg-card}
     * containers on the page.
     */
    private Locator mobileCards() {
        return page.locator("div.rounded-lg.border.bg-card.p-4");
    }

    /**
     * Wait until the list has finished its initial render, in a breakpoint-agnostic way. On
     * desktop/tablet the DataTable renders a {@code <table>} (wait for the header row); on the mobile
     * breakpoint the table is replaced by {@code DataTableCards} (a stack of {@code div.bg-card}
     * cards) so there is no {@code <thead>} — wait for the first card instead. Waiting for either
     * signal lets the same step work at every viewport (FOR-QA-AUTO-05 mobile scenarios).
     */
    public DataTablePage waitUntilLoaded() {
        page.locator("thead tr th, div.rounded-lg.border.bg-card.p-4").first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** A mobile card ({@code DataTableCards}) that contains {@code text} (e.g. a project name). */
    public Locator cardByText(String text) {
        return mobileCards().filter(new Locator.FilterOptions().setHasText(text));
    }

    /**
     * A clickable list entry containing {@code text}, working at every breakpoint without a racy
     * snapshot decision: a single combined selector matches BOTH the desktop/tablet table body row
     * ({@code tbody tr}) and the mobile {@code DataTableCards} card (the exact
     * {@code rounded-lg border bg-card p-4} combo), filtered to the ones containing {@code text}.
     * Exactly one representation is mounted per breakpoint, so {@code .first()} on the result is the
     * intended entry. Playwright auto-waits on the returned locator, so callers do not need the
     * table/cards to have finished rendering when this is called.
     */
    public Locator rowOrCardByText(String text) {
        return page.locator("tbody tr, div.rounded-lg.border.bg-card.p-4")
                .filter(new Locator.FilterOptions().setHasText(text));
    }

    // ---- Structure ----

    private Locator table() {
        return page.locator("table");
    }

    /** The header cells of the (first) table. */
    public Locator headerCells() {
        return page.locator("thead tr th");
    }

    /** The data rows of the (first) table body. */
    public Locator rows() {
        return page.locator("tbody tr");
    }

    // ---- Columns ----

    /**
     * The localized text of each column header, in order. Reads the header cell text (the label
     * lives in a {@code <span>} inside each {@code <th>}); trailing filter/sort icon buttons have no
     * text, so the cell's text is the column label.
     */
    public List<String> columnHeaders() {
        List<String> headers = new ArrayList<>();
        Locator cells = headerCells();
        int count = cells.count();
        for (int i = 0; i < count; i++) {
            String text = cells.nth(i).innerText();
            headers.add(text == null ? "" : text.trim());
        }
        return headers;
    }

    // ---- Rows ----

    /** Number of data rows currently rendered on the page. */
    public int rowCount() {
        return rows().count();
    }

    /** A row that contains the given text anywhere in its cells (e.g. a generated email/code). */
    public Locator rowByText(String text) {
        return rows().filter(new Locator.FilterOptions().setHasText(text));
    }

    /** {@code true} when at least one row contains {@code text}. */
    public boolean containsRow(String text) {
        return rowByText(text).count() > 0;
    }

    // ---- Search ----

    private Locator searchInput() {
        return page.locator("input.pl-9");
    }

    /**
     * Type into the list search box. The frontend debounces the query (~300ms) and refetches, so
     * callers should assert the resulting rows with Playwright's auto-retrying assertions rather
     * than reading {@link #rowCount()} immediately.
     */
    public void search(String text) {
        Locator input = searchInput();
        input.fill(text);
    }

    /** Clear the search box. */
    public void clearSearch() {
        searchInput().fill("");
    }

    // ---- Pagination ----

    /**
     * The pagination previous/next buttons. They are icon buttons rendered as a leading "previous"
     * and a trailing "next" within the pagination row; located here by their order among the
     * pagination controls.
     */
    public Locator previousPageButton() {
        return paginationButtons().first();
    }

    /** The next-page button (last of the two pagination arrow buttons). */
    public Locator nextPageButton() {
        Locator buttons = paginationButtons();
        int count = buttons.count();
        return count == 0 ? buttons : buttons.nth(count - 1);
    }

    private Locator paginationButtons() {
        // The pagination footer holds exactly the prev/next icon buttons (the page-size select is a
        // separate combobox trigger). Scope to buttons that sit alongside the "n / m" indicator.
        return page.locator("button:has(svg.lucide-chevron-left), button:has(svg.lucide-chevron-right)");
    }

    /** Go to the next page (no-op-safe: the button is disabled on the last page). */
    public void nextPage() {
        nextPageButton().click();
    }

    /** Go to the previous page (no-op-safe: the button is disabled on the first page). */
    public void previousPage() {
        previousPageButton().click();
    }

    /**
     * {@code true} when pagination controls are present (a multi-page or paginated list). Absent on
     * an empty list (the footer is only rendered when there is data).
     */
    public boolean hasPagination() {
        return paginationButtons().count() > 0;
    }
}
