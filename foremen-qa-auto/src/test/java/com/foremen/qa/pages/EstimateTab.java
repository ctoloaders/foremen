package com.foremen.qa.pages;

import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the estimate surface rendered on the workspace {@code estimate} tab
 * (frontend {@code EstimateTab.tsx} / {@code EstimateHeader.tsx}, FOR-05-05/07). Reached at
 * {@code /projects/{projectId}/estimate}.
 *
 * <p>Confirmed testids: container {@code estimate-tab}; the native package {@code <select>}
 * {@code estimate-package-select} (option index 0 is the placeholder, real packages follow);
 * {@code estimate-apply-package}; {@code estimate-apply-cheapest}; {@code estimate-save};
 * {@code estimate-prepare-offer} (shown to an executor with {@code OFFERS:CREATE}); the fill
 * indicator value {@code estimate-fill-indicator-pct} (i18n {@code fillIndicatorValue} carrying the
 * percent); the unsaved badge {@code estimate-unsaved} (present only while staged edits exist).
 *
 * <p>Sequencing note (verified against {@code EstimateTab.tsx}): Apply package and Apply cheapest
 * both PREVIEW + STAGE edits (client-side), and the batched {@code Save} persists them. The fill
 * indicator recomputes live from the overlaid matrix, and {@code estimate-prepare-offer} always
 * creates the offer and navigates to {@code /pricing} on success (R21). This object holds NO
 * assertions.
 */
public final class EstimateTab {

    private final Page page;

    public EstimateTab(Page page) {
        this.page = page;
    }

    // ---- Navigation ----

    /** Navigate to the estimate surface at {@code /projects/{projectId}/estimate}. */
    public EstimateTab open(long projectId) {
        page.navigate(ProjectWorkspacePage.workspaceTabUrl(projectId, "estimate"));
        return this;
    }

    // ---- Structure ----

    /** The estimate tab container. */
    public Locator container() {
        return testId("estimate-tab");
    }

    /** Wait until the estimate tab container and its package selector have rendered. */
    public EstimateTab waitUntilRendered() {
        container().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        packageSelect().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // Let in-flight data loads settle so the per-step report screenshot captures a fully-rendered
        // page rather than a mid-load one. Best-effort and bounded: swallow a timeout so a page that
        // keeps a long-poll / websocket open can never hang the step.
        settleNetwork();
        return this;
    }

    /** Wait for network idle, bounded and non-throwing (screenshot-quality aid only). */
    private void settleNetwork() {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (RuntimeException ignored) {
            // Best-effort: proceed if idle can't be reached within the default timeout.
        }
        // NETWORKIDLE alone is not enough: the estimate matrix renders from React-Query data that
        // paints skeleton (.animate-pulse) placeholders until the query resolves AND re-renders. Wait
        // for those skeletons to clear so the per-step report screenshot captures the real matrix.
        // Bounded + swallow timeout so it can't hang.
        try {
            page.waitForFunction(
                    "() => document.querySelectorAll('.animate-pulse').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(5000));
        } catch (RuntimeException ignored) {
            // Best-effort.
        }
    }

    // ---- Package selection ----

    private Locator packageSelect() {
        return testId("estimate-package-select");
    }

    /**
     * Select the FIRST real (non-placeholder) package in the native {@code <select>}. Index 0 is the
     * empty placeholder option; the real packages start at index 1. Waits until at least one real
     * option is available (the packages are loaded asynchronously) before selecting it.
     */
    public void selectFirstPackage() {
        Locator select = packageSelect().first();
        // Wait until the options beyond the placeholder are present.
        page.waitForCondition(() -> optionValues(select).size() > 1);
        select.selectOption(new SelectOption().setIndex(1));
    }

    @SuppressWarnings("unchecked")
    private List<String> optionValues(Locator select) {
        Object values = select.evaluate(
                "el => Array.from(el.options).map(o => o.value)");
        return (List<String>) values;
    }

    // ---- Header actions ----

    /** Click "Apply package" (stages the package's calculated result). */
    public void clickApplyPackage() {
        click("estimate-apply-package");
    }

    /** Click "Apply cheapest" (stages the cheapest concrete product across the estimate). */
    public void clickApplyCheapest() {
        click("estimate-apply-cheapest");
    }

    /** Click "Save" to persist the staged set. */
    public void clickSave() {
        click("estimate-save");
    }

    /**
     * Wait until the staged edits are persisted: the unsaved badge disappears AND the Save button is
     * disabled (the header disables Save when there are no pending changes). Bounded by the default
     * action timeout via Playwright's condition wait.
     */
    public void waitUntilSaved() {
        // The amber unsaved badge is only rendered while staged edits exist; wait for it to vanish.
        page.waitForCondition(() -> testId("estimate-unsaved").count() == 0);
        // And confirm the Save control is disabled (no pending changes).
        page.waitForCondition(() -> {
            Locator save = testId("estimate-save");
            return save.count() > 0 && !save.first().isEnabled();
        });
    }

    /** Click "Prepare offer" (always creates the offer and navigates to the pricing tab, R21). */
    public void clickPrepareOffer() {
        click("estimate-prepare-offer");
    }

    // ---- Fill indicator ----

    /** The fill-indicator percent element (i18n value text carrying the percent). */
    public Locator fillIndicatorPct() {
        return testId("estimate-fill-indicator-pct");
    }

    /** The trimmed innerText of the fill-indicator percent (e.g. RU "Готовность: 100%"). */
    public String fillPercentText() {
        Locator pct = fillIndicatorPct();
        pct.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        String text = pct.first().innerText();
        return text == null ? "" : text.trim();
    }

    // ---- Cell report (CellReport.tsx) ----

    /**
     * Localized values of the i18n key {@code estimate.branch.finishing} the CellReport row renders
     * via {@code t(`estimate.branch.${line.branch}`)}. The live docker stack serves the frontend with
     * its own default locale, which is PL ("Wykończeniowe") — not RU — so matching only the RU value
     * silently finds nothing. Match against BOTH the RU ("Отделочные") and PL ("Wykończeniowe") labels
     * so the finishing-line detection is locale-robust.
     */
    private static final List<String> FINISHING_BRANCH_LABELS =
            List.of("Отделочные", "Wykończeniowe");

    /**
     * Expand every collapsed work-type group so its per-cell {@code estimate-cell-*} buttons render.
     * The matrix groups start COLLAPSED and only emit their cell buttons when expanded
     * (WorkTypeGroupSection.tsx renders work rows / cells only {@code when expanded}), so the assigned
     * cells are invisible until each group header ({@code estimate-group-header-*}) with
     * {@code aria-expanded="false"} is clicked. Idempotent: already-expanded groups are skipped.
     */
    public void expandAllGroups() {
        Locator headers = page.locator("[data-testid^='estimate-group-header-']");
        int count = headers.count();
        for (int i = 0; i < count; i++) {
            Locator header = headers.nth(i);
            String expanded = header.getAttribute("aria-expanded");
            if (!"true".equals(expanded)) {
                header.click();
            }
        }
    }

    /**
     * The {@code data-testid}s of every ASSIGNED matrix cell (a cell with a concrete/placeholder
     * material set, i.e. {@code data-assigned="true"}). Clicking such a cell opens the CellReport
     * dialog. Returns an empty list when no cells are assigned yet. Expands all groups first so the
     * cell buttons are rendered (they exist in the DOM only for expanded groups).
     */
    public List<String> assignedCellTestIds() {
        expandAllGroups();
        Locator cells = page.locator("[data-testid^='estimate-cell-'][data-assigned='true']");
        int count = cells.count();
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = cells.nth(i).getAttribute("data-testid");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Click the assigned matrix cell with the given testid and wait for its CellReport to open. The
     * matrix's first column is a {@code position: sticky} work-name header that can overlap a cell
     * horizontally and intercept a normal click (Playwright then times out on the actionability
     * check). The cell IS the real click target (its own handler opens the report), so a forced click
     * (bypassing the overlap/actionability check) is the correct, reliable way to open it.
     */
    public void openCell(String cellTestId) {
        Locator cell = page.locator("[data-testid='" + cellTestId + "']");
        cell.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        cell.first().scrollIntoViewIfNeeded();
        // A positional/force click lands on the element's center, which for the LEFTMOST room cell is
        // still under the sticky work-name column and hits the sticky <th> instead — so the dialog
        // never opens. Invoke the cell button's own click handler directly in the DOM: this fires the
        // React onClick without pointer hit-testing, immune to the sticky-column overlap.
        cell.first().evaluate("el => el.click()");
        cellReport().first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
    }

    /** The open CellReport dialog container. */
    private Locator cellReport() {
        return testId("estimate-cell-report");
    }

    /** {@code true} while the CellReport dialog is open/visible. */
    public boolean cellReportOpen() {
        return cellReport().count() > 0 && cellReport().first().isVisible();
    }

    /**
     * Close the open CellReport dialog. The shadcn Dialog closes on Escape; if the dialog is still
     * visible, fall back to the footer "Close" button ({@code common.close}). Waits until the dialog
     * is hidden.
     */
    public void closeCellReport() {
        if (!cellReportOpen()) {
            return;
        }
        page.keyboard().press("Escape");
        try {
            cellReport().first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(2000));
            return;
        } catch (RuntimeException ignored) {
            // Escape didn't dismiss it; fall back to the explicit footer "Close" button. The label is
            // locale-dependent (RU "Закрыть" / PL "Zamknij"), so match either, and if neither is
            // found, click the LAST button in the dialog (the footer Close is always rightmost).
        }
        Locator closeByName = cellReport().getByRole(
                com.microsoft.playwright.options.AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile("Закрыть|Zamknij")));
        if (closeByName.count() > 0) {
            closeByName.first().click();
        } else {
            Locator buttons = cellReport().locator("button");
            int n = buttons.count();
            if (n > 0) {
                buttons.nth(n - 1).click();
            }
        }
        cellReport().first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN));
    }

    /**
     * In the currently-open CellReport, the line ids of every CONCRETE (chosen) PACKAGE FINISHING
     * line — i.e. every line that carries BOTH:
     * <ul>
     *   <li>an un-choose eraser button {@code estimate-cell-report-line-unchoose-{lineId}} (rendered
     *       only on a concrete/chosen line), AND</li>
     *   <li>the from-package badge {@code estimate-cell-report-line-from-package-{lineId}} (rendered
     *       only when {@code appliedFromPackage} is true).</li>
     * </ul>
     * The from-package badge is the primary and sufficient filter: construction lines are never
     * package-sourced, so a from-package line is always a finishing line. This matters because the
     * offer tab renders ONLY {@code appliedFromPackage} finishing lines as placeholders; un-choosing
     * a NON-package finishing line would never surface as an unchosen position on the offer tab.
     */
    public List<String> concreteFinishingUnchooseIdsInOpenReport() {
        Locator erasers = cellReport().locator(
                "[data-testid^='estimate-cell-report-line-unchoose-']");
        int count = erasers.count();
        List<String> lineIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String testId = erasers.nth(i).getAttribute("data-testid");
            if (testId == null) {
                continue;
            }
            String lineId = testId.substring("estimate-cell-report-line-unchoose-".length());
            if (lineId.isEmpty() || !lineId.chars().allMatch(Character::isDigit)) {
                continue;
            }
            // Keep only PACKAGE lines: the from-package badge must be present for this line id.
            Locator fromPackage = cellReport().locator(
                    "[data-testid='estimate-cell-report-line-from-package-" + lineId + "']");
            if (fromPackage.count() > 0) {
                lineIds.add(lineId);
            }
        }
        return lineIds;
    }

    /**
     * Parse the bare numeric line id from a {@code estimate-cell-report-line-{id}} testid, returning
     * {@code null} for any sub-element testid ({@code …-state-}, {@code …-unchoose-}, …) so only the
     * top-level line rows are matched.
     */
    private String pureLineId(String testId) {
        if (testId == null) {
            return null;
        }
        String prefix = "estimate-cell-report-line-";
        if (!testId.startsWith(prefix)) {
            return null;
        }
        String rest = testId.substring(prefix.length());
        // A pure line row's remainder is only digits (the line id). Sub-elements carry an extra
        // alphabetic segment (e.g. "state-7", "unchoose-7"), so reject anything non-numeric.
        return rest.matches("\\d+") ? rest : null;
    }

    /**
     * Un-choose (clear back to Placeholder) the concrete line {@code lineId} in the open CellReport:
     * click its eraser, then wait until the line's state chip reads the placeholder label and its
     * eraser disappears (the eraser renders only on concrete lines). Bounded by the page's condition
     * wait.
     */
    public void unchooseLineInOpenReport(String lineId) {
        Locator eraser = page.locator(
                "[data-testid='estimate-cell-report-line-unchoose-" + lineId + "']");
        eraser.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        eraser.first().click();
        // After un-choosing, the row flips to a placeholder: data-concrete="false" and the eraser is
        // no longer rendered. Wait for the eraser to vanish as the authoritative signal.
        page.waitForCondition(() -> page.locator(
                "[data-testid='estimate-cell-report-line-unchoose-" + lineId + "']").count() == 0);
    }

    // ---- Internals ----

    private Locator testId(String id) {
        return page.locator("[data-testid=" + id + "]");
    }

    private void click(String id) {
        Locator target = testId(id);
        target.first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE));
        target.first().click();
    }
}
