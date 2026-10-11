package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the {@code readiness} workspace tab panel (FOR-05-11), mirroring the real frontend
 * {@code ReadinessTab} / {@code ReadinessAreaRow} / {@code ReadinessDonut} components.
 *
 * <p>The {@code readiness} tab is a design-stage workspace tab (key {@code readiness}, gated by
 * {@code PROJECTS} READ) that renders the aggregated {@code GET /api/projects/{id}/readiness} view:
 * an overall donut + overall-state label, then one ordered row per {@code Area_Contribution}
 * (rooms → estimate → offer → documents → team → schedule, limited to the caller's readable areas),
 * each with a localized name, a localized state label, a percentage, and its outstanding items.
 *
 * <p>Locators key off the confirmed testids shipped by FOR-05-11:
 * <ul>
 *   <li>panel states {@code readiness-tab} / {@code readiness-tab-loading} /
 *       {@code readiness-tab-error} / {@code readiness-tab-empty}, retry {@code readiness-retry},</li>
 *   <li>overall donut {@code readiness-donut} + its percentage {@code readiness-donut-pct}, and the
 *       overall-state label {@code readiness-overall-state},</li>
 *   <li>the areas list {@code readiness-areas} and, per area {@code <key>}, the row
 *       {@code readiness-area-<key>} (attr {@code data-state}), its activatable header
 *       {@code readiness-area-header-<key>}, the state label {@code readiness-area-state-<key>}, the
 *       percentage {@code readiness-area-pct-<key>}, the items list {@code readiness-area-items-<key>},
 *       and each outstanding item {@code readiness-item-<key>-<index>}.</li>
 * </ul>
 * The area {@code key}s are: {@code rooms}, {@code estimate}, {@code offer}, {@code documents},
 * {@code team}, {@code schedule}. Holds no assertions — steps assert against these queries.
 */
public final class ReadinessTab {

    /** The workspace tab key for the readiness panel (also its {@code WORKSPACE_TABS} key). */
    public static final String TAB_KEY = "readiness";

    private final Page page;

    public ReadinessTab(Page page) {
        this.page = page;
    }

    // ---- Panel container + transient states ----

    /** The loaded readiness panel container. */
    public Locator panel() {
        return page.locator("[data-testid=readiness-tab]");
    }

    /** {@code true} once the loaded readiness panel container is visible. */
    public boolean isRendered() {
        return panel().count() > 0 && panel().first().isVisible();
    }

    /** Wait until the loaded readiness panel container has rendered. */
    public ReadinessTab waitUntilRendered() {
        panel().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** The non-blocking loading skeleton state. */
    public Locator loadingState() {
        return page.locator("[data-testid=readiness-tab-loading]");
    }

    /** The localized error state (shown on a failed aggregated read). */
    public Locator errorState() {
        return page.locator("[data-testid=readiness-tab-error]");
    }

    /** The error-state retry button that re-issues the aggregated read. */
    public Locator retryButton() {
        return page.locator("[data-testid=readiness-retry]");
    }

    /** The localized empty state (no readable area → 0% / BLOCKED). */
    public Locator emptyState() {
        return page.locator("[data-testid=readiness-tab-empty]");
    }

    /** {@code true} once the empty state is visible. */
    public boolean isEmptyStateShown() {
        return emptyState().count() > 0 && emptyState().first().isVisible();
    }

    /** Wait until the empty state has rendered. */
    public ReadinessTab waitUntilEmptyStateShown() {
        emptyState().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    // ---- Overall donut + overall state ----

    /** The overall-readiness donut. */
    public Locator donut() {
        return page.locator("[data-testid=readiness-donut]");
    }

    /** The overall-percentage text inside the donut (e.g. {@code 58%}). */
    public Locator donutPercentage() {
        return page.locator("[data-testid=readiness-donut-pct]");
    }

    /** The trimmed overall-percentage text (e.g. {@code 58%}), or {@code ""} when absent. */
    public String donutPercentageText() {
        Locator pct = donutPercentage();
        if (pct.count() == 0) {
            return "";
        }
        String text = pct.first().innerText();
        return text == null ? "" : text.trim();
    }

    /** The localized overall-state label (DONE / PARTIAL / BLOCKED resolved via i18n). */
    public Locator overallState() {
        return page.locator("[data-testid=readiness-overall-state]");
    }

    /** The trimmed overall-state label text, or {@code ""} when absent. */
    public String overallStateText() {
        Locator state = overallState();
        if (state.count() == 0) {
            return "";
        }
        String text = state.first().innerText();
        return text == null ? "" : text.trim();
    }

    // ---- Area rows ----

    /** The ordered area-rows list container. */
    public Locator areasList() {
        return page.locator("[data-testid=readiness-areas]");
    }

    /** The row for area {@code key} (attr {@code data-state}). */
    public Locator areaRow(String key) {
        return page.locator("[data-testid=readiness-area-" + key + "]");
    }

    /** {@code true} when the row for area {@code key} is present and visible. */
    public boolean isAreaRowShown(String key) {
        Locator row = areaRow(key);
        return row.count() > 0 && row.first().isVisible();
    }

    /** The {@code data-state} of the area row for {@code key} (DONE / PARTIAL / BLOCKED), or null. */
    public String areaRowState(String key) {
        Locator row = areaRow(key);
        return row.count() == 0 ? null : row.first().getAttribute("data-state");
    }

    /** The activatable header button of the area row for {@code key} (jumps to its tab). */
    public Locator areaHeader(String key) {
        return page.locator("[data-testid=readiness-area-header-" + key + "]");
    }

    /** Click the area header for {@code key} (navigates the workspace to the area's target tab). */
    public void clickAreaHeader(String key) {
        areaHeader(key).first().click();
    }

    /** The localized state label element of the area row for {@code key}. */
    public Locator areaStateLabel(String key) {
        return page.locator("[data-testid=readiness-area-state-" + key + "]");
    }

    /** The trimmed localized state-label text of the area row for {@code key}, or {@code ""}. */
    public String areaStateLabelText(String key) {
        Locator label = areaStateLabel(key);
        if (label.count() == 0) {
            return "";
        }
        String text = label.first().innerText();
        return text == null ? "" : text.trim();
    }

    /** The percentage element of the area row for {@code key} (e.g. {@code 50%}). */
    public Locator areaPercentage(String key) {
        return page.locator("[data-testid=readiness-area-pct-" + key + "]");
    }

    /** The trimmed percentage text of the area row for {@code key} (e.g. {@code 50%}), or {@code ""}. */
    public String areaPercentageText(String key) {
        Locator pct = areaPercentage(key);
        if (pct.count() == 0) {
            return "";
        }
        String text = pct.first().innerText();
        return text == null ? "" : text.trim();
    }

    /** The outstanding-items list of the area row for {@code key} (absent for a fully-ready area). */
    public Locator areaItems(String key) {
        return page.locator("[data-testid=readiness-area-items-" + key + "]");
    }

    /** The {@code index}-th outstanding item of the area row for {@code key} (0-based). */
    public Locator areaItem(String key, int index) {
        return page.locator("[data-testid=readiness-item-" + key + "-" + index + "]");
    }

    /** The ordered list of area keys currently rendered, in DOM (declared) order. */
    public java.util.List<String> renderedAreaKeys() {
        Locator rows = areasList().locator("[data-testid^=readiness-area-]");
        java.util.List<String> keys = new java.util.ArrayList<>();
        int count = rows.count();
        for (int i = 0; i < count; i++) {
            String testId = rows.nth(i).getAttribute("data-testid");
            if (testId == null) {
                continue;
            }
            // Keep only the row containers (readiness-area-<key>), not the nested
            // readiness-area-header/state/pct/items testids that also start with the prefix.
            String rest = testId.substring("readiness-area-".length());
            if (!rest.contains("-")) {
                keys.add(rest);
            }
        }
        return keys;
    }
}
