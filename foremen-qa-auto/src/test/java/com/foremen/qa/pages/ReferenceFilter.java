package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object for the reference (association) filter component (Requirement 8.5).
 *
 * <p>On a list page, a reference-backed column exposes a filter <b>popover</b> opened from the Filter
 * icon button in that column's header ({@code DataTableHeader}). The popover renders the
 * {@code ReferenceFilter}: a clear-all control, a search {@code input[type=text]}, and an
 * infinite-scroll options list where each option is a {@code role="option"} row containing a
 * {@code role="checkbox"} button (multi-select toggle) and a name button (single-pick that replaces
 * the selection and closes the popover).
 *
 * <p>Column labels are localized, so a specific column's filter is opened by matching the header
 * cell that contains the (caller-supplied, localized or partial) column label and clicking its
 * filter trigger. Once open, the popover content is unique on the page, so options are located
 * globally by {@code role=option}.
 */
public final class ReferenceFilter {

    private final Page page;

    public ReferenceFilter(Page page) {
        this.page = page;
    }

    // ---- Opening the filter for a column ----

    /**
     * Open the filter popover for the column whose header contains {@code columnLabel}. Clicks the
     * Filter icon button inside that {@code <th>}.
     */
    public ReferenceFilter open(String columnLabel) {
        Locator header = page.locator("thead tr th").filter(
                new Locator.FilterOptions().setHasText(columnLabel));
        // The header cell contains the sort button and the Filter icon button; the filter trigger is
        // the button with the filter aria-label. Fall back to the last button in the cell (the
        // filter control renders after the sort control).
        Locator filterButtons = header.first().locator("button");
        filterButtons.last().click();
        // Wait for the reference search box (popover content) to appear.
        searchBox().waitFor();
        return this;
    }

    /**
     * Open the filter popover for the column at the given zero-based header index. Locale-independent
     * alternative to {@link #open(String)} for callers that know a reference column's position but
     * not its localized label (e.g. the work-catalog category column is index 1). Clicks the Filter
     * icon button (the last button) inside that {@code <th>}.
     */
    public ReferenceFilter openByHeaderIndex(int columnIndex) {
        Locator header = page.locator("thead tr th").nth(columnIndex);
        header.locator("button").last().click();
        searchBox().waitFor();
        return this;
    }

    // ---- Interactions within the open popover ----

    private Locator searchBox() {
        // The reference popover's search input is a bare text input with placeholder; scope to a
        // text input that is a sibling of the options list. It is the only free text input rendered
        // inside a popover, so a role=textbox lookup within the open popover is unambiguous.
        return page.getByRole(AriaRole.TEXTBOX).last();
    }

    /** Type into the reference options search (debounced, backend-searched). */
    public ReferenceFilter search(String text) {
        searchBox().fill(text);
        return this;
    }

    /** The option rows currently rendered in the popover. */
    public Locator options() {
        return page.getByRole(AriaRole.OPTION);
    }

    /** An option row whose visible name contains {@code name}. */
    public Locator optionByText(String name) {
        return options().filter(new Locator.FilterOptions().setHasText(name));
    }

    /**
     * Single-pick: click the option's name, replacing the whole selection with that one value and
     * closing the popover — the "select one" gesture the reference filter implements.
     */
    public void selectSingle(String name) {
        // The name is the non-checkbox button inside the option row.
        Locator option = optionByText(name).first();
        option.getByRole(AriaRole.BUTTON).last().click();
    }

    /** Toggle an option's membership via its checkbox (multi-select), keeping the popover open. */
    public void toggle(String name) {
        Locator option = optionByText(name).first();
        option.getByRole(AriaRole.CHECKBOX).click();
    }

    /**
     * Click the clear-all control to empty the selection (drops the filter). The control sits in the
     * popover header and carries an {@code X} icon; it is disabled while nothing is selected.
     */
    public void clear() {
        Locator clearButton = page.locator("button:has(svg.lucide-x)").first();
        if (clearButton.count() > 0 && clearButton.isEnabled()) {
            clearButton.click();
        }
    }

    /** Number of option rows currently visible in the popover. */
    public int optionCount() {
        return options().count();
    }
}
